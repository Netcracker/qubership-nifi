package org.qubership.nifi;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.eclipse.aether.RepositorySystemSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.processor.validation.ContentValidatorProcessor;
import org.qubership.nifi.reporting.ComponentPrometheusReportingTask;
import org.qubership.nifi.service.validation.JsonContentValidator;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyDocumentationGenerateDocumentationTest {

    @TempDir
    Path tempDir;

    private PropertyDocumentation mojo;
    private DependencyNode mockDepNode;
    private DependencyGraphBuilder mockDepGraphBuilder;
    private Path outputFile;

    private static byte[] readResource(String name) throws Exception {
        try (InputStream is = PropertyDocumentationGenerateDocumentationTest.class
                .getResourceAsStream("/" + name)) {
            return is.readAllBytes();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Write template and config files into tempDir
        Path templatePath = tempDir.resolve("template.md");
        Files.write(templatePath, readResource("template.md"));

        Path configPath = tempDir.resolve("config.yaml");
        Files.write(configPath, readResource("config.yaml"));

        outputFile = tempDir.resolve("user-guide.md");

        // NAR-packaged project mock
        MavenProject mockProject = mock(MavenProject.class);
        when(mockProject.getPackaging()).thenReturn("nar");
        when(mockProject.getArtifactId()).thenReturn("test-nar");

        // Top-level project provides the base directory for relative path resolution
        MavenProject mockTopLevelProject = mock(MavenProject.class);
        when(mockTopLevelProject.getBasedir()).thenReturn(tempDir.toFile());

        MavenSession mockSession = mock(MavenSession.class);
        when(mockSession.getTopLevelProject()).thenReturn(mockTopLevelProject);
        when(mockSession.getUserProperties()).thenReturn(new Properties());

        // Locate the test-processors JAR via its class's ProtectionDomain
        File jarFile = new File(
                ContentValidatorProcessor.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());

        // NAR artifact (project.getArtifact()) and the test-processors artifact discovered as a dependency
        Artifact narArtifact = mock(Artifact.class);
        Artifact testProcessorsArtifact = mock(Artifact.class);

        // Stub compareTo so TreeSet ordering is stable and remove(narArtifact) does not
        // accidentally remove testProcessorsArtifact (default Mockito compareTo returns 0)
        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == narArtifact) ? 0 : -1;
        }).when(narArtifact).compareTo(any());
        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == testProcessorsArtifact) ? 0 : 1;
        }).when(testProcessorsArtifact).compareTo(any());

        // Non-qubership groupId prevents a recursive getNarDependencies() call
        when(testProcessorsArtifact.getGroupId()).thenReturn("test");
        when(testProcessorsArtifact.getFile()).thenReturn(jarFile);
        when(mockProject.getArtifact()).thenReturn(narArtifact);

        // ProjectBuilder: build(narArtifact, ...) → result whose project is mockNarProject
        MavenProject mockNarProject = mock(MavenProject.class);
        ProjectBuildingResult mockBuildingResult = mock(ProjectBuildingResult.class);
        when(mockBuildingResult.getProject()).thenReturn(mockNarProject);

        ProjectBuilder mockProjectBuilder = mock(ProjectBuilder.class);
        when(mockProjectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)))
                .thenReturn(mockBuildingResult);

        // Dependency graph: visitor visits testProcessorsArtifact (happy-path default)
        mockDepNode = mock(DependencyNode.class);
        when(mockDepNode.getArtifact()).thenReturn(testProcessorsArtifact);
        doAnswer(inv -> {
            DependencyNodeVisitor visitor = inv.getArgument(0);
            visitor.visit(mockDepNode);
            visitor.endVisit(mockDepNode);
            return null;
        }).when(mockDepNode).accept(any(DependencyNodeVisitor.class));

        mockDepGraphBuilder = mock(DependencyGraphBuilder.class);
        when(mockDepGraphBuilder.buildDependencyGraph(any(), any())).thenReturn(mockDepNode);

        // Assemble the mojo with all required fields injected via reflection
        mojo = new PropertyDocumentation();
        mojo.setLog(mock(Log.class));
        setField(mojo, "project", mockProject);
        setField(mojo, "session", mockSession);
        setField(mojo, "repoSession", mock(RepositorySystemSession.class));
        setField(mojo, "dependencyGraphBuilder", mockDepGraphBuilder);
        setField(mojo, "projectBuilder", mockProjectBuilder);
        setField(mojo, "artifactResolver", mock(ArtifactResolver.class));
        // Relative paths: resolved against tempDir (the top-level project basedir)
        setField(mojo, "outputFileTemplatePath", "template.md");
        setField(mojo, "outputFilePath", "user-guide.md");
        setField(mojo, "artifactExcludedListPath", "config.yaml");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // --- Sunny-day tests ---

    @Test
    void testGenerateDocumentationDocumentsAnnotatedProcessor() throws Exception {
        mojo.execute();

        String output = Files.readString(outputFile);
        assertTrue(output.contains("ContentValidatorProcessor"),
                "Output should document ContentValidatorProcessor (has @CapabilityDescription)");
        assertTrue(output.contains("Validates content"),
                "Output should contain the @CapabilityDescription text");
        assertTrue(output.contains("Content Validator Service"),
                "Output should contain the property display name");
    }

    @Test
    void testGenerateDocumentationIgnoresProcessorWithoutCapabilityDescription() throws Exception {
        mojo.execute();

        String output = Files.readString(outputFile);
        assertFalse(output.contains("BulkDistributedMapCacheProcessor"),
                "Output should not document BulkDistributedMapCacheProcessor (no @CapabilityDescription)");
    }

    @Test
    void testGenerateDocumentationDocumentsAnnotatedControllerService() throws Exception {
        File jarFile = new File(
                JsonContentValidator.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());

        Artifact narArtifact = mock(Artifact.class);
        Artifact serviceArtifact = mock(Artifact.class);

        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == narArtifact) ? 0 : -1;
        }).when(narArtifact).compareTo(any());
        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == serviceArtifact) ? 0 : 1;
        }).when(serviceArtifact).compareTo(any());

        when(serviceArtifact.getGroupId()).thenReturn("test");
        when(serviceArtifact.getFile()).thenReturn(jarFile);

        DependencyNode serviceDepNode = mock(DependencyNode.class);
        when(serviceDepNode.getArtifact()).thenReturn(serviceArtifact);
        doAnswer(inv -> {
            DependencyNodeVisitor visitor = inv.getArgument(0);
            visitor.visit(serviceDepNode);
            visitor.endVisit(serviceDepNode);
            return null;
        }).when(serviceDepNode).accept(any(DependencyNodeVisitor.class));

        when(mockDepGraphBuilder.buildDependencyGraph(any(), any())).thenReturn(serviceDepNode);

        mojo.execute();

        String output = Files.readString(outputFile);
        assertTrue(output.contains("JsonContentValidator"),
                "Output should document JsonContentValidator (has @CapabilityDescription)");
        assertTrue(output.contains("check the JSON against a given schema"),
                "Output should contain the @CapabilityDescription text");
        assertTrue(output.contains("Schema"),
                "Output should contain the property display name");
    }

    @Test
    void testGenerateDocumentationDocumentsAnnotatedReportingTask() throws Exception {
        File jarFile = new File(
                ComponentPrometheusReportingTask.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());

        Artifact narArtifact = mock(Artifact.class);
        Artifact processorImplArtifact = mock(Artifact.class);

        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == narArtifact) ? 0 : -1;
        }).when(narArtifact).compareTo(any());
        doAnswer(inv -> {
            Artifact other = inv.getArgument(0);
            return (other == processorImplArtifact) ? 0 : 1;
        }).when(processorImplArtifact).compareTo(any());

        when(processorImplArtifact.getGroupId()).thenReturn("test");
        when(processorImplArtifact.getFile()).thenReturn(jarFile);

        DependencyNode processorImplDepNode = mock(DependencyNode.class);
        when(processorImplDepNode.getArtifact()).thenReturn(processorImplArtifact);
        doAnswer(inv -> {
            DependencyNodeVisitor visitor = inv.getArgument(0);
            visitor.visit(processorImplDepNode);
            visitor.endVisit(processorImplDepNode);
            return null;
        }).when(processorImplDepNode).accept(any(DependencyNodeVisitor.class));

        when(mockDepGraphBuilder.buildDependencyGraph(any(), any())).thenReturn(processorImplDepNode);

        mojo.execute();

        String output = Files.readString(outputFile);
        assertTrue(output.contains("ComponentPrometheusReportingTask"),
                "Output should document ComponentPrometheusReportingTask (has @CapabilityDescription)");
        assertTrue(output.contains("Sends components"),
                "Output should contain the @CapabilityDescription text");
        assertTrue(output.contains("Processor time threshold"),
                "Output should contain the property display name");
    }

    // --- Rainy-day / edge-case tests ---

    @Test
    void testGenerateDocumentationWithEmptyDependencyGraph() throws Exception {
        // Override: accept() only calls endVisit so no artifact enters the dependency set
        doAnswer(inv -> {
            DependencyNodeVisitor visitor = inv.getArgument(0);
            visitor.endVisit(mockDepNode);
            return null;
        }).when(mockDepNode).accept(any(DependencyNodeVisitor.class));

        // Use the platform classloader as the thread-context classloader so the
        // URLClassLoader (created with empty URL array) cannot find test processors
        // through parent-classloader delegation during ServiceLoader discovery.
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
        try {
            mojo.execute();
        } finally {
            Thread.currentThread().setContextClassLoader(originalCL);
        }

        assertTrue(Files.exists(outputFile),
                "Output file should be created from the template even with an empty dependency graph");
        String output = Files.readString(outputFile);
        assertFalse(output.contains("ContentValidatorProcessor"),
                "Output should contain no processor rows when the dependency graph is empty");
    }

    @Test
    void testExecuteThrowsWhenOutputFilePathIsInvalid() throws Exception {
        // Point the output file at a non-existent subdirectory so Files.copy() will fail
        setField(mojo, "outputFilePath", "nonexistent-dir/user-guide.md");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("Failed to reset output file from template"),
                "Exception message should report failure to reset output file from template");
    }
}
