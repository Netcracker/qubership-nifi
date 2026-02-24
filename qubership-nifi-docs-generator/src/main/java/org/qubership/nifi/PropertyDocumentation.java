package org.qubership.nifi;

import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExclusionSetFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.mock.MockReportingInitializationContext;
import org.apache.nifi.processor.Processor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.reporting.ReportingTask;
import org.apache.nifi.mock.MockProcessorInitializationContext;
import org.apache.nifi.mock.MockControllerServiceInitializationContext;
import org.eclipse.aether.RepositorySystemSession;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.qubership.nifi.utils.MarkdownUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import java.io.IOException;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.qubership.nifi.ComponentType.CONTROLLER_SERVICE;
import static org.qubership.nifi.ComponentType.PROCESSOR;
import static org.qubership.nifi.ComponentType.REPORTING_TASK;

@Mojo(name = "generate", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PropertyDocumentation extends AbstractMojo {

    private Set<String> excludedArtifactIds;
    private static final String KEY_EXCLUDE_ARRAY = "excludedArtifacts";
    private static final String SESSION_RESET_KEY = "docs.generator.reset";

    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;
    @Component
    private ProjectBuilder projectBuilder;
    @Component
    private ArtifactResolver artifactResolver;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "doc.template.file", defaultValue = "/docs/template/user-guide-template.md",
            readonly = false, required = true)
    private String outputFileTemplatePath;

    @Parameter(property = "doc.output.file", defaultValue = "/docs/user-guide.md",
            readonly = false, required = true)
    private String outputFilePath;

    @Parameter(property = "doc.exclude.artifact.file", defaultValue = "/docs/template/documentGeneratorConfig.yaml",
            readonly = false, required = true)
    private String artifactExcludedListPath;

    /**
     * Method for generating documentation for custom components.
     *
     * @throws MojoExecutionException
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (!"nar".equalsIgnoreCase(project.getPackaging())) {
            getLog().info("Skipping execution for module with packaging '" + project.getPackaging()
                    + "'. Only 'nar' packaging is supported.");
            return;
        }

        File topLevelBasedir = session.getTopLevelProject().getBasedir();

        File artifactExcludedListFile = new File(topLevelBasedir, artifactExcludedListPath);
        Set<String> excludedIds = readExcludedArtifactsFromFile(artifactExcludedListFile);
        excludedArtifactIds = Collections.unmodifiableSet(excludedIds);
        File outputFileTemplate = new File(topLevelBasedir, outputFileTemplatePath);
        if (!outputFileTemplate.exists() || !outputFileTemplate.isFile()) {
            throw new MojoExecutionException("File specified in the parameter 'doc.template.file' does not exists."
                    + " 'doc.template.file' = " + outputFileTemplate.getAbsolutePath());
        }

        File outputFile = new File(topLevelBasedir, outputFilePath);
        String resetKey = SESSION_RESET_KEY + "." + outputFile.getAbsolutePath();
        if (session.getUserProperties().getProperty(resetKey) == null) {
            try {
                Files.copy(outputFileTemplate.toPath(), outputFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                session.getUserProperties().setProperty(resetKey, "true");
                getLog().info("Reset output file to template: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to reset output file from template", e);
            }
        }

        try {
            generateDocumentation(outputFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate documentation for custom components.", e);
        }
    }

    private static final int MAX_ALIASES_FOR_COLLECTIONS = 50;

    private Set<String> readExcludedArtifactsFromFile(File configFile) {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(MAX_ALIASES_FOR_COLLECTIONS);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        try (InputStream inputStream = new FileInputStream(configFile)) {
            Map<String, Object> data = yaml.load(inputStream);

            if (data != null) {
                Object listObj = data.get(KEY_EXCLUDE_ARRAY);
                if (listObj instanceof List) {
                    List<String> list = (List<String>) listObj;

                    Set<String> artifactSet = new HashSet<>(list);
                    return artifactSet;
                } else {
                    getLog().error("Key '" + KEY_EXCLUDE_ARRAY + "' not found or is not a list in configuration"
                            + " file specified in the parameter 'doc.exclude.artifact.file' = "
                            + configFile.getAbsolutePath());
                    return Collections.emptySet();
                }
            } else {
                getLog().error("Configuration file specified in the parameter 'doc.exclude.artifact.file'"
                        + " is empty or could not be parsed as a Map. 'doc.exclude.artifact.file' = "
                        + configFile.getAbsolutePath());
                return Collections.emptySet();
            }
        } catch (IOException e) {
            getLog().error("IO Error reading configuration file specified in the parameter"
                    + " 'doc.exclude.artifact.file' =  " + configFile.getAbsolutePath() + " - " + e.getMessage());
            getLog().error("Error: ", e);
            return Collections.emptySet();
        } catch (Exception e) {
            getLog().error("YAML parsing error in configuration file specified in the parameter "
                    + " 'doc.exclude.artifact.file' = " + configFile.getAbsolutePath() + " - " + e.getMessage());
            getLog().error("Error: ", e);
            return Collections.emptySet();
        }
    }

    private void generateDocumentation(
            File outputFile
    ) throws IOException, ProjectBuildingException, MojoExecutionException {
        getLog().info("Generating documentation for Custom Components");
        Artifact narArtifact = project.getArtifact();
        final Set<Artifact> narArtifacts = getNarDependencies(narArtifact);

        final Set<URL> urls = new HashSet<>();
        for (final Artifact artifact : narArtifacts) {
            final Set<URL> artifactUrls = toURLs(artifact);
            urls.addAll(artifactUrls);
        }

        URL[] urlsArray = urls.toArray(new URL[0]);
        ClassLoader parentClassLoader = Thread.currentThread().getContextClassLoader();

        getLog().debug("URLClassLoader created successfully with " + urls.size() + " entries.");

        MarkdownUtils markdownUtils = new MarkdownUtils(outputFile.toPath(), getLog());
        markdownUtils.readFile();
        try (URLClassLoader componentClassLoader = new URLClassLoader(urlsArray, parentClassLoader)) {
            ServiceLoader<Processor> processorServiceLoader = ServiceLoader.load(Processor.class, componentClassLoader);
            List<CustomComponentEntity> customComponentList = new ArrayList<>();
            for (Processor processorInstance : processorServiceLoader) {
                Class<? extends Processor> processorClass = processorInstance.getClass();
                ProcessorInitializationContext initializationContext = new MockProcessorInitializationContext();
                processorInstance.initialize(initializationContext);
                CapabilityDescription capabilityDescriptionAnnotationProc =
                        processorClass.getAnnotation(CapabilityDescription.class);
                List<PropertyDescriptor> propertyDescriptors = processorInstance.getPropertyDescriptors();
                if (capabilityDescriptionAnnotationProc != null) {
                    String processorName = processorClass.getSimpleName();
                    String descriptionValue = capabilityDescriptionAnnotationProc.value();
                    List<PropertyDescriptorEntity> componentProperties =
                            generateComponentPropertiesList(propertyDescriptors, descriptionValue);
                    customComponentList.add(new CustomComponentEntity(processorName, PROCESSOR, project.getArtifactId(),
                            descriptionValue, componentProperties));
                }
            }

            ServiceLoader<ControllerService> controllerServiceServiceLoader =
                    ServiceLoader.load(ControllerService.class, componentClassLoader);
            for (ControllerService controllerServiceInstance : controllerServiceServiceLoader) {
                Class<? extends ControllerService> controllerServiceClass = controllerServiceInstance.getClass();
                CapabilityDescription capabilityDescriptionAnnotationCS =
                        controllerServiceClass.getAnnotation(CapabilityDescription.class);
                ControllerServiceInitializationContext controllerServiceInitializationContext =
                        new MockControllerServiceInitializationContext();
                controllerServiceInstance.initialize(controllerServiceInitializationContext);
                List<PropertyDescriptor> propertyDescriptors = controllerServiceInstance.getPropertyDescriptors();
                if (capabilityDescriptionAnnotationCS != null) {
                    String controllerServiceName = controllerServiceClass.getSimpleName();
                    String descriptionValue = capabilityDescriptionAnnotationCS.value();
                    List<PropertyDescriptorEntity> componentProperties =
                            generateComponentPropertiesList(propertyDescriptors, descriptionValue);
                    customComponentList.add(new CustomComponentEntity(controllerServiceName, CONTROLLER_SERVICE,
                            project.getArtifactId(), descriptionValue, componentProperties));
                }
            }

            ServiceLoader<ReportingTask> reportingTaskServiceLoader =
                    ServiceLoader.load(ReportingTask.class, componentClassLoader);
            for (ReportingTask reportingTaskInstance : reportingTaskServiceLoader) {
                Class<? extends ReportingTask> reportingTaskClass = reportingTaskInstance.getClass();
                ReportingInitializationContext reportingInitializationContext =
                        new MockReportingInitializationContext();
                reportingTaskInstance.initialize(reportingInitializationContext);
                CapabilityDescription capabilityDescriptionAnnotationRT = reportingTaskClass
                        .getAnnotation(CapabilityDescription.class);
                List<PropertyDescriptor> propertyDescriptors = reportingTaskInstance.getPropertyDescriptors();
                if (capabilityDescriptionAnnotationRT != null) {
                    String reportingTaskName = reportingTaskClass.getSimpleName();
                    String descriptionValue = capabilityDescriptionAnnotationRT.value();
                    List<PropertyDescriptorEntity> componentProperties =
                            generateComponentPropertiesList(propertyDescriptors, descriptionValue);
                    customComponentList.add(new CustomComponentEntity(reportingTaskName, REPORTING_TASK,
                            project.getArtifactId(), descriptionValue, componentProperties));
                }
            }

            List<CustomComponentEntity> processorEntities = customComponentList.stream()
                    .filter(entity -> PROCESSOR.equals(entity.getType()))
                    .collect(Collectors.toList());

            if (!processorEntities.isEmpty()) {
                markdownUtils.generateTable(processorEntities, PROCESSOR);
                markdownUtils.generatePropertyDescription(processorEntities, PROCESSOR);
            }

            List<CustomComponentEntity> controllerServiceEntities = customComponentList.stream()
                    .filter(entity -> CONTROLLER_SERVICE.equals(entity.getType()))
                    .collect(Collectors.toList());

            if (!controllerServiceEntities.isEmpty()) {
                markdownUtils.generateTable(controllerServiceEntities, CONTROLLER_SERVICE);
                markdownUtils.generatePropertyDescription(controllerServiceEntities, CONTROLLER_SERVICE);
            }

            List<CustomComponentEntity> reportingTaskEntities = customComponentList.stream()
                    .filter(entity -> REPORTING_TASK.equals(entity.getType()))
                    .collect(Collectors.toList());

            if (!reportingTaskEntities.isEmpty()) {
                markdownUtils.generateTable(reportingTaskEntities, REPORTING_TASK);
                markdownUtils.generatePropertyDescription(reportingTaskEntities, REPORTING_TASK);
            }

        } catch (ServiceConfigurationError e) {
            getLog().error("Failed to load services", e);
            throw new MojoExecutionException("Failed to load services for documentation generation", e);
        } catch (InitializationException e) {
            getLog().error("Failed to initialize component", e);
            throw new MojoExecutionException("Failed to initialize component", e);
        }
        markdownUtils.writeToFile();
    }

    private List<PropertyDescriptorEntity> generateComponentPropertiesList(
            List<PropertyDescriptor> propertyDescriptors,
            String componentDescription
    ) {
        List<PropertyDescriptorEntity> customComponentEntityList = new ArrayList<>();
        for (PropertyDescriptor propDesc : propertyDescriptors) {
            customComponentEntityList.add(new PropertyDescriptorEntity(
                    propDesc.getDisplayName(), propDesc.getName(), propDesc.getDefaultValue(),
                    propDesc.getDescription() != null ? propDesc.getDescription().replaceAll("\\r?\\n|\\r", "") : "",
                    propDesc.getAllowableValues(),
                    componentDescription));
        }
        return customComponentEntityList;
    }

    private Set<Artifact> getNarDependencies(
            final Artifact narArtifact
    ) throws MojoExecutionException, ProjectBuildingException {
        final ProjectBuildingRequest narRequest = createProjectBuildingRequest();
        final ProjectBuildingResult narResult = projectBuilder.build(narArtifact, narRequest);

        final Set<Artifact> narDependencies = gatherArtifacts(narResult.getProject(), TreeSet::new);
        narDependencies.remove(narArtifact);
        narDependencies.remove(project.getArtifact());

        getLog().debug("Found NAR dependency of " + narArtifact
                + ", which resolved to the following artifacts: " + narDependencies);
        Set<Artifact> artifactsToAdd = new HashSet<>();
        for (Artifact artifact : narDependencies) {
            if ("org.qubership.nifi".equals(artifact.getGroupId())) {
                try {
                    Set<Artifact> childNarArtifacts = getNarDependencies(artifact);
                    childNarArtifacts.removeIf(childArtifact -> !"provided".equals(childArtifact.getScope()));
                    artifactsToAdd.addAll(childNarArtifacts);
                } catch (Exception e) {
                    getLog().warn("Failed to get dependencies for artifact "
                            + artifact.getId() + ". Reason: " + e.getMessage(), e);
                }
            }
        }
        narDependencies.addAll(artifactsToAdd);

        return narDependencies;
    }

    private Set<Artifact> gatherArtifacts(
            final MavenProject mavenProject,
            final Supplier<Set<Artifact>> setSupplier
    ) throws MojoExecutionException {
        final Set<Artifact> artifacts = setSupplier.get();
        final DependencyNodeVisitor nodeVisitor = new DependencyNodeVisitor() {
            @Override
            public boolean visit(final DependencyNode dependencyNode) {
                final Artifact artifact = dependencyNode.getArtifact();
                artifacts.add(artifact);
                return true;
            }

            @Override
            public boolean endVisit(final DependencyNode dependencyNode) {
                return true;
            }
        };

        try {
            final ProjectBuildingRequest projectRequest = createProjectBuildingRequest();
            projectRequest.setProject(mavenProject);

            final ArtifactFilter excludesFilter = new ExclusionSetFilter(excludedArtifactIds);
            final DependencyNode depNode = dependencyGraphBuilder.buildDependencyGraph(projectRequest, excludesFilter);
            depNode.accept(nodeVisitor);
        } catch (DependencyGraphBuilderException e) {
            throw new MojoExecutionException("Failed to build dependency tree", e);
        }
        return artifacts;
    }

    private ProjectBuildingRequest createProjectBuildingRequest() {
        final ProjectBuildingRequest projectRequest = new DefaultProjectBuildingRequest();
        projectRequest.setRepositorySession(repoSession);
        projectRequest.setSystemProperties(System.getProperties());
        return projectRequest;
    }

    private Set<URL> toURLs(
            final Artifact artifact
    ) throws MojoExecutionException {
        final Set<URL> urls = new HashSet<>();

        final File artifactFile = artifact.getFile();
        if (artifactFile == null) {
            getLog().debug("Attempting to resolve Artifact " + artifact + " because it has no File associated with it");

            final ArtifactResolutionRequest request = new ArtifactResolutionRequest();
            request.setArtifact(artifact);

            final ArtifactResolutionResult result = artifactResolver.resolve(request);
            if (!result.isSuccess()) {
                throw new MojoExecutionException("Could not resolve local dependency " + artifact);
            }

            getLog().debug("Resolved Artifact " + artifact + " to " + result.getArtifacts());

            for (final Artifact resolved : result.getArtifacts()) {
                urls.addAll(toURLs(resolved));
            }
        } else {
            try {
                final URL url = artifact.getFile().toURI().toURL();
                getLog().debug("Adding URL " + url + " to ClassLoader");
                urls.add(url);
            } catch (final MalformedURLException mue) {
                throw new MojoExecutionException("Failed to convert File " + artifact.getFile() + " into URL", mue);
            }
        }

        return urls;
    }
}
