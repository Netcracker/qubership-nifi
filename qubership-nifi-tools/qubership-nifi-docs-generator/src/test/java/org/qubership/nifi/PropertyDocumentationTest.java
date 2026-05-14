package org.qubership.nifi;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests for basic functions of {@link PropertyDocumentation} not involving dependency processing. */
class PropertyDocumentationTest {

    @TempDir
    private Path tempDir;

    /**
     * Returns the temporary directory used by this test.
     * @return path to temporary directory
     */
    Path getTempDir() {
        return tempDir;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeReadExcludedArtifactsFromFile(PropertyDocumentation mojo, File file) throws Exception {
        Method method = PropertyDocumentation.class.getDeclaredMethod("readExcludedArtifactsFromFile", File.class);
        method.setAccessible(true);
        return (Set<String>) method.invoke(mojo, file);
    }

    /** Verifies execute() is a no-op for non-NAR packaged projects. */
    @Test
    void testExecuteSkipsNonNarPackaging() throws Exception {
        MavenProject mockProject = mock(MavenProject.class);
        when(mockProject.getPackaging()).thenReturn("jar");

        PropertyDocumentation mojo = new PropertyDocumentation();
        setField(mojo, "project", mockProject);

        assertDoesNotThrow(mojo::execute);
    }

    /** Verifies valid YAML config file is parsed into the excluded artifacts set. */
    @Test
    void testReadExcludedArtifactsFromFileWithValidYamlReturnsParsedSet() throws Exception {
        Path yamlFile = tempDir.resolve("config.yaml");
        Files.writeString(yamlFile,
                "excludedArtifacts:\n  - artifact-one\n  - artifact-two\n");

        PropertyDocumentation mojo = new PropertyDocumentation();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertEquals(2, result.size());
        assertTrue(result.contains("artifact-one"));
        assertTrue(result.contains("artifact-two"));
    }

    /** Verifies execute() throws when the template file does not exist. */
    @Test
    void testExecuteWithMissingTemplateFileThrowsMojoExecutionException() throws Exception {
        MavenProject mockProject = mock(MavenProject.class);
        when(mockProject.getPackaging()).thenReturn("nar");

        MavenProject mockTopLevelProject = mock(MavenProject.class);
        when(mockTopLevelProject.getBasedir()).thenReturn(tempDir.toFile());

        MavenSession mockSession = mock(MavenSession.class);
        when(mockSession.getTopLevelProject()).thenReturn(mockTopLevelProject);
        when(mockSession.getUserProperties()).thenReturn(new Properties());

        PropertyDocumentation mojo = new PropertyDocumentation();
        setField(mojo, "project", mockProject);
        setField(mojo, "session", mockSession);
        setField(mojo, "outputFileTemplatePath", "/nonexistent-template.md");
        setField(mojo, "outputFilePath", "/docs/user-guide.md");
        setField(mojo, "artifactExcludedListPath", "/nonexistent-config.yaml");

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    /** Verifies missing YAML config file returns an empty set. */
    @Test
    void testReadExcludedArtifactsFromFileWithMissingFileReturnsEmptySet() throws Exception {
        File missing = tempDir.resolve("missing.yaml").toFile();

        PropertyDocumentation mojo = new PropertyDocumentation();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, missing);

        assertTrue(result.isEmpty());
    }

    /** Verifies an empty YAML config file returns an empty set. */
    @Test
    void testReadExcludedArtifactsFromFileWithEmptyYamlReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("empty.yaml");
        Files.write(yamlFile, new byte[0]);

        PropertyDocumentation mojo = new PropertyDocumentation();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }

    /** Verifies a YAML file with invalid syntax returns an empty set. */
    @Test
    void testReadExcludedArtifactsFromFileWithInvalidYamlSyntaxReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("invalid.yaml");
        Files.writeString(yamlFile, ": invalid: yaml: content: [\n");

        PropertyDocumentation mojo = new PropertyDocumentation();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }

    /** Verifies a YAML file without the excludedArtifacts key returns an empty set. */
    @Test
    void testReadExcludedArtifactsFromFileWithMissingExcludedArtifactsKeyReturnsEmptySet() throws Exception {
        Path yamlFile = tempDir.resolve("no-key.yaml");
        Files.writeString(yamlFile, "otherKey:\n  - value\n");

        PropertyDocumentation mojo = new PropertyDocumentation();
        Set<String> result = invokeReadExcludedArtifactsFromFile(mojo, yamlFile.toFile());

        assertTrue(result.isEmpty());
    }
}
