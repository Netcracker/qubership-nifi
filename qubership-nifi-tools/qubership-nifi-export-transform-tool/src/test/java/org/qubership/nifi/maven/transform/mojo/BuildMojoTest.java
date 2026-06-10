package org.qubership.nifi.maven.transform.mojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class BuildMojoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG_YAML = """
            processorTypes:
              - org.qubership.nifi.TestProcessor:
                  query.sql: SQL Query
            """;

    private static final String FLOW_WITH_REFERENCE = """
            {
              "flowContents": {
                "name": "root", "identifier": "root-id",
                "processors": [{
                  "name": "MyProcessor",
                  "type": "org.qubership.nifi.TestProcessor",
                  "identifier": "proc-id",
                  "properties": {"SQL Query": "@flowConf_flow/MyProcessor/query.sql"}
                }],
                "processGroups": []
              }
            }
            """;

    @TempDir
    Path tempDir;

    @Mock
    Log log;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private BuildMojo mojo(Path configFile) throws Exception {
        return mojoWithOptions(configFile, tempDir, false);
    }

    private BuildMojo mojoWithOptions(Path configFile, Path exportDir, boolean delete)
            throws Exception {
        BuildMojo m = new BuildMojo();
        m.setLog(log);
        setField(AbstractTransformMojo.class, m, "configFile", configFile.toFile());
        setField(AbstractTransformMojo.class, m, "exportDir", exportDir.toFile());
        setField(BuildMojo.class, m, "delete", delete);
        return m;
    }

    private void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Path createExtractedFile(String content) throws IOException {
        Path file = tempDir.resolve("flowConf_flow").resolve("MyProcessor").resolve("query.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void executeThrowsMojoExecutionExceptionWhenConfigFileNotFound() throws Exception {
        BuildMojo m = mojo(tempDir.resolve("missing.yaml"));

        assertThrows(MojoExecutionException.class, m::execute);
    }

    @Test
    void executeWarnsAndReturnsWhenConfigHasNoProcessorTypes() throws Exception {
        Path configFile = write("config.yaml", "processorTypes: []\n");

        assertDoesNotThrow(mojo(configFile)::execute);
    }

    @Test
    void executeRestoresPropertyValueFromExtractedFile() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", FLOW_WITH_REFERENCE);
        createExtractedFile("SELECT 1");

        mojo(configFile).execute();

        JsonNode flow = MAPPER.readTree(tempDir.resolve("flow.json").toFile());
        String value = flow.get("flowContents").get("processors").get(0)
                .get("properties").get("SQL Query").asText();
        assertEquals("SELECT 1", value);
    }

    @Test
    void executeThrowsMojoFailureExceptionWhenReferencedFileMissing() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", FLOW_WITH_REFERENCE);

        assertThrows(MojoFailureException.class, mojo(configFile)::execute);
    }

    @Test
    void executeThrowsMojoExecutionExceptionOnIOException() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        Path nonExistentDir = tempDir.resolve("nonexistent");

        assertThrows(MojoExecutionException.class,
                mojoWithOptions(configFile, nonExistentDir, false)::execute);
    }

    @Test
    void executeWithDeleteTrueRemovesFlowConfDirectory() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", FLOW_WITH_REFERENCE);
        Path flowConfDir = createExtractedFile("SELECT 1").getParent().getParent();

        mojoWithOptions(configFile, tempDir, true).execute();

        assertFalse(Files.exists(flowConfDir));
    }
}
