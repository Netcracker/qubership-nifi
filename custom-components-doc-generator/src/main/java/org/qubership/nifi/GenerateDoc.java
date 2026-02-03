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
import org.eclipse.aether.RepositorySystemSession;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.qubership.nifi.utils.MarkdownUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

@Mojo(name = "generate", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class GenerateDoc extends AbstractMojo {

    private Set<String> EXCLUDED_ARTIFACT_IDS;

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

    @Parameter(property = "doc.template.file", defaultValue = "/doc-template/custom-components-doc-template.md",
            readonly = true, required = true)
    private String outputFileTemplatePath;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;


    @Parameter(property = "doc.exclude.artifact.file", defaultValue = "/doc-template/artifactExcludedList.yaml",
            readonly = true, required = true)
    private String artifactExcludedListPath;

    @Override
    public void execute() throws MojoExecutionException {
        if (!"nar".equalsIgnoreCase(project.getPackaging())) {
            getLog().info("Skipping execution for module with packaging '" + project.getPackaging()
                    + "'. Only 'nar' packaging is supported.");
            return;
        }

        File topLevelBasedir = session.getTopLevelProject().getBasedir();
        File outputFile = new File(topLevelBasedir, outputFileTemplatePath);
        if (!outputFile.exists() || !outputFile.isFile()) {
            throw new MojoExecutionException("Input file does not exist or is not a file"
                    + " relative to top-level project: " + outputFile.getAbsolutePath());
        }

        File artifactExcludedListFile = new File(topLevelBasedir, artifactExcludedListPath);
        Set<String> excludedIds = readExcludedArtifactsFromFile(artifactExcludedListFile);
        EXCLUDED_ARTIFACT_IDS = Collections.unmodifiableSet(excludedIds);

        try {
            generateDocumentation(outputFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate documentation for custom components.", e);
        }
    }

    public static Set<String> readExcludedArtifactsFromFile(File configFile) {
        if (configFile == null || !configFile.exists() || !configFile.isFile()) {
            System.err.println("Configuration file does not exist or is not a file: "
                    + (configFile != null ? configFile.getAbsolutePath() : "null"));
            return Collections.emptySet();
        }

        Yaml yaml = new Yaml();

        try (InputStream inputStream = new FileInputStream(configFile)) {
            java.util.Map<String, Object> data = yaml.load(inputStream);

            if (data != null) {
                Object listObj = data.get("excluded_artifact");
                if (listObj instanceof List) {
                    List<String> list = (List<String>) listObj;

                    Set<String> artifactSet = new HashSet<>(list);
                    return artifactSet;
                } else {
                    System.err.println("Key 'excluded_artifact' not found or is not a list in YAML file: "
                            + configFile.getAbsolutePath());
                    return Collections.emptySet();
                }
            } else {
                System.err.println("YAML file is empty or could not be parsed as a Map: "
                        + configFile.getAbsolutePath());
                return Collections.emptySet();
            }
        } catch (IOException e) {
            System.err.println("IO Error reading YAML file: " + configFile.getAbsolutePath() + " - " + e.getMessage());
            e.printStackTrace();
            return Collections.emptySet();
        } catch (Exception e) {
            System.err.println("YAML parsing error in file: " + configFile.getAbsolutePath() + " - " + e.getMessage());
            e.printStackTrace();
            return Collections.emptySet();
        }
    }

    private void generateDocumentation(
            File outputFile
    ) throws IOException, ProjectBuildingException, MojoExecutionException {
        getLog().info("Generating documentation for Custom Components");
        final URLClassLoader componentClassLoader;
        Artifact narArtifact = project.getArtifact();
        final Set<Artifact> narArtifacts = getNarDependencies(narArtifact);

        final Set<URL> urls = new HashSet<>();
        for (final Artifact artifact : narArtifacts) {
            final Set<URL> artifactUrls = toURLs(artifact);
            urls.addAll(artifactUrls);
        }

        URL[] urlsArray = urls.toArray(new URL[0]);
        ClassLoader parentClassLoader = Thread.currentThread().getContextClassLoader();
        componentClassLoader = new URLClassLoader(urlsArray, parentClassLoader);

        getLog().info("URLClassLoader created successfully with " + urls.size() + " entries.");

        MarkdownUtils markdownUtils = new MarkdownUtils(outputFile.toPath());
        try {
            ServiceLoader<Processor> processorServiceLoader = ServiceLoader.load(Processor.class, componentClassLoader);
            Map<String, List<CustomComponentEntity>> processorEntityMap = new HashMap<>();
            List<String[]> processorRowsList = new ArrayList<>();
            for (Processor processorInstance : processorServiceLoader) {
                Class<? extends Processor> processorClass = processorInstance.getClass();
                ProcessorInitializationContext initializationContext = new MockProcessorInitializationContext();
                processorInstance.initialize(initializationContext);
                CapabilityDescription capabilityDescriptionAnnotationProc =
                        processorClass.getAnnotation(CapabilityDescription.class);
                List<PropertyDescriptor> propertyDescriptors = processorInstance.getPropertyDescriptors();
                if (capabilityDescriptionAnnotationProc != null) {
                    String processorName = processorClass.getSimpleName();
                    String descriptionValue = capabilityDescriptionAnnotationProc.value().replaceAll("\\r?\\n|\\r", "");
                    String[] row = {processorName, project.getArtifactId(), descriptionValue};
                    processorRowsList.add(row);
                    if (propertyDescriptors != null) {
                        processorEntityMap.put(
                                processorName, generateComponentPropertiesList(propertyDescriptors, descriptionValue)
                        );
                    }
                }
            }
            String[][] processorArrays = processorRowsList.toArray(new String[processorRowsList.size()][]);
            if (processorArrays.length != 0) {
                markdownUtils.generateTable(processorArrays, "processor");
                markdownUtils.generatePropertyDescription(processorEntityMap, "processor");
            }

            ServiceLoader<ControllerService> controllerServiceServiceLoader =
                    ServiceLoader.load(ControllerService.class, componentClassLoader);
            List<String[]> controllerServiceRowsList = new ArrayList<>();
            Map<String, List<CustomComponentEntity>> controllerServiceEntityMap = new HashMap<>();
            for (ControllerService controllerServiceInstance : controllerServiceServiceLoader) {
                Class<? extends ControllerService> controllerServiceClass = controllerServiceInstance.getClass();
                CapabilityDescription capabilityDescriptionAnnotationCS =
                        controllerServiceClass.getAnnotation(CapabilityDescription.class);
                List<PropertyDescriptor> propertyDescriptors = controllerServiceInstance.getPropertyDescriptors();
                if (capabilityDescriptionAnnotationCS != null) {
                    String controllerServiceName = controllerServiceClass.getSimpleName();
                    String descriptionValue = capabilityDescriptionAnnotationCS.value().replaceAll("\\r?\\n|\\r", "");
                    String[] csRow = {controllerServiceName, project.getArtifactId(), descriptionValue};
                    controllerServiceRowsList.add(csRow);
                    if (propertyDescriptors != null) {
                        controllerServiceEntityMap.put(controllerServiceName,
                                generateComponentPropertiesList(propertyDescriptors, descriptionValue));
                    }
                }
            }
            String[][] controllerServiceArrays =
                    controllerServiceRowsList.toArray(new String[controllerServiceRowsList.size()][]);
            if (controllerServiceArrays.length != 0) {
                markdownUtils.generateTable(controllerServiceArrays, "controller_service");
                markdownUtils.generatePropertyDescription(controllerServiceEntityMap, "controller_service");
            }

            ServiceLoader<ReportingTask> reportingTaskServiceLoader =
                    ServiceLoader.load(ReportingTask.class, componentClassLoader);
            List<String[]> reportingTaskRowsList = new ArrayList<>();
            Map<String, List<CustomComponentEntity>> reportingTaskEntityMap = new HashMap<>();
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
                    String descriptionValue = capabilityDescriptionAnnotationRT.value().replaceAll("\\r?\\n|\\r", "");
                    String[] rtRow = {reportingTaskName, project.getArtifactId(), descriptionValue};
                    reportingTaskRowsList.add(rtRow);
                    if (propertyDescriptors != null) {
                        reportingTaskEntityMap.put(reportingTaskName,
                                generateComponentPropertiesList(propertyDescriptors, descriptionValue));
                    }
                }
            }
            String[][] reportingTaskArrays = reportingTaskRowsList.toArray(new String[reportingTaskRowsList.size()][]);
            if (reportingTaskArrays.length != 0) {
                markdownUtils.generateTable(reportingTaskArrays, "reporting_task");
                markdownUtils.generatePropertyDescription(reportingTaskEntityMap, "reporting_task");
            }
        } catch (ServiceConfigurationError e) {
            System.err.println("ServiceConfigurationError: " + e.getMessage());
            e.printStackTrace();
        } catch (InitializationException e) {
            throw new RuntimeException(e);
        } finally {
            componentClassLoader.close();
        }
    }

    private List<CustomComponentEntity> generateComponentPropertiesList(
            List<PropertyDescriptor> propertyDescriptors,
            String componentDescription
    ) {
        List<CustomComponentEntity> customComponentEntityList = new ArrayList<>();
        for (PropertyDescriptor propDesc : propertyDescriptors) {
            customComponentEntityList.add(new CustomComponentEntity(
                    propDesc.getDisplayName(), propDesc.getName(), propDesc.getDefaultValue(),
                    propDesc.getDescription().replaceAll("\\r?\\n|\\r", ""), propDesc.getAllowableValues(),
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

            final ArtifactFilter excludesFilter = new ExclusionSetFilter(EXCLUDED_ARTIFACT_IDS);
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
        projectRequest.setUserProperties(System.getProperties());
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
