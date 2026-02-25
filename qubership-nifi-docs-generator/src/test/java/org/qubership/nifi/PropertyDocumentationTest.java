package org.qubership.nifi;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertyDocumentationTest {

    @TempDir
    Path tempDir;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Log mockLog() {
        return mock(Log.class);
    }

    private PropertyDocumentation createMojo() throws Exception {
        PropertyDocumentation mojo = new PropertyDocumentation();
        // Inject a mock Log via the inherited setLog method
        mojo.setLog(mockLog());
        return mojo;
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeReadExcludedArtifactsFromFile(PropertyDocumentation mojo, File file) throws Exception {
        Method method = PropertyDocumentation.class.getDeclaredMethod("readExcludedArtifactsFromFile", File.class);
        method.setAccessible(true);
        return (Set<String>) method.invoke(mojo, file);
    }

    // --- Sunny day tests ---

    @Test
    void testExecuteSkipsNonNarPackaging() throws Exception {
        MavenProject mockProject = mock(MavenProject.class);
        when(mockProject.getPackaging()).thenReturn("jar");

        PropertyDocumentation mojo = createMojo();
        setField(mojo, "project", mockProject);

        assertDoesNotThrow(mojo::execute);
    }

    @Test
    void testReadExcludedArtifactsFromFileWithValidYamlReturnsParsedSet() throws Exception {
        Path yamlFile = tempDir.resolve("config.yaml");
        Files.write(yamlFile,
                "excludedArtifacts:\n  - artifact-one\n  - artifact-two\n".getBytes(StandardCharsets.UTF_8));

        PropertyDocumentation mojo = createMojo();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertEquals(2, result.size());
        assertTrue(result.contains("artifact-one"));
        assertTrue(result.contains("artifact-two"));
    }

    // --- Rainy day tests ---

    @Test
    void testExecuteWithMissingTemplateFileThrowsMojoExecutionException() throws Exception {
        MavenProject mockProject = mock(MavenProject.class);
        when(mockProject.getPackaging()).thenReturn("nar");

        MavenProject mockTopLevelProject = mock(MavenProject.class);
        when(mockTopLevelProject.getBasedir()).thenReturn(tempDir.toFile());

        MavenSession mockSession = mock(MavenSession.class);
        when(mockSession.getTopLevelProject()).thenReturn(mockTopLevelProject);
        when(mockSession.getUserProperties()).thenReturn(new Properties());

        PropertyDocumentation mojo = createMojo();
        setField(mojo, "project", mockProject);
        setField(mojo, "session", mockSession);
        setField(mojo, "outputFileTemplatePath", "/nonexistent-template.md");
        setField(mojo, "outputFilePath", "/docs/user-guide.md");
        setField(mojo, "artifactExcludedListPath", "/nonexistent-config.yaml");

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    void testReadExcludedArtifactsFromFileWithMissingFileReturnsEmptySet() throws Exception {
        File missing = tempDir.resolve("missing.yaml").toFile();

        PropertyDocumentation mojo = createMojo();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, missing);

        assertTrue(result.isEmpty());
    }

    @Test
    void testReadExcludedArtifactsFromFileWithEmptyYamlReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("empty.yaml");
        Files.write(yamlFile, new byte[0]);

        PropertyDocumentation mojo = createMojo();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }

    @Test
    void testReadExcludedArtifactsFromFileWithInvalidYamlSyntaxReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("invalid.yaml");
        Files.write(yamlFile, ": invalid: yaml: content: [\n".getBytes(StandardCharsets.UTF_8));

        PropertyDocumentation mojo = createMojo();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }

    @Test
    void testReadExcludedArtifactsFromFileWithMissingExcludedArtifactsKeyReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("no-key.yaml");
        Files.write(yamlFile, "otherKey:\n  - value\n".getBytes(StandardCharsets.UTF_8));

        PropertyDocumentation mojo = createMojo();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }
}
