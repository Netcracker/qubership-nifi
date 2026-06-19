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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ExtractMojoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG_YAML = """
            processorTypes:
              - org.qubership.nifi.TestProcessor:
                  query.sql: SQL Query
            """;

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    /**
     * Returns the temporary directory used by this test.
     * @return path to temporary directory
     */
    Path getTempDir() {
        return tempDir;
    }

    /** Mock Maven logger. */
    @Mock
    private Log log;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private ExtractMojo mojo(Path configFile) throws Exception {
        return mojoWithExportDir(configFile, tempDir);
    }

    private ExtractMojo mojoWithExportDir(Path configFile, Path exportDir) throws Exception {
        ExtractMojo m = new ExtractMojo();
        m.setLog(log);
        setField(AbstractTransformMojo.class, m, "configFile", configFile.toFile());
        setField(AbstractTransformMojo.class, m, "exportDir", exportDir.toFile());
        return m;
    }

    private void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void executeThrowsMojoExecutionExceptionWhenConfigFileNotFound() throws Exception {
        ExtractMojo m = mojo(tempDir.resolve("missing.yaml"));

        assertThrows(MojoExecutionException.class, m::execute);
    }

    @Test
    void executeWarnsAndReturnsWhenConfigHasNoProcessorTypes() throws Exception {
        Path configFile = write("config.yaml", "processorTypes: []\n");
        ExtractMojo m = mojo(configFile);

        assertDoesNotThrow(m::execute);
    }

    @Test
    void executeExtractsPropertyValueToFile() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [{
                      "name": "MyProcessor",
                      "type": "org.qubership.nifi.TestProcessor",
                      "identifier": "proc-id",
                      "properties": {"SQL Query": "SELECT 1"}
                    }],
                    "processGroups": []
                  }
                }
                """);

        mojo(configFile).execute();

        Path extractedFile = tempDir.resolve("flowConf_flow")
                .resolve("MyProcessor").resolve("query.sql");
        assertTrue(Files.exists(extractedFile));
        assertEquals("SELECT 1", Files.readString(extractedFile));
    }

    @Test
    void executeReplacesPropertyWithReferenceInFlowJson() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [{
                      "name": "MyProcessor",
                      "type": "org.qubership.nifi.TestProcessor",
                      "identifier": "proc-id",
                      "properties": {"SQL Query": "SELECT 1"}
                    }],
                    "processGroups": []
                  }
                }
                """);

        mojo(configFile).execute();

        JsonNode flow = MAPPER.readTree(tempDir.resolve("flow.json").toFile());
        String ref = flow.get("flowContents").get("processors").get(0)
                .get("properties").get("SQL Query").asText();
        assertEquals("@flowConf_flow/MyProcessor/query.sql", ref);
    }

    @Test
    void executeThrowsMojoFailureExceptionWhenValidationFails() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        write("flow.json", """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [
                      {"name": "DupProc", "type": "org.qubership.nifi.TestProcessor",
                       "identifier": "id-1", "properties": {}},
                      {"name": "DupProc", "type": "org.qubership.nifi.TestProcessor",
                       "identifier": "id-2", "properties": {}}
                    ],
                    "processGroups": []
                  }
                }
                """);

        assertThrows(MojoFailureException.class, mojo(configFile)::execute);
    }

    @Test
    void executeThrowsMojoExecutionExceptionOnIOException() throws Exception {
        Path configFile = write("config.yaml", CONFIG_YAML);
        Path nonExistentDir = tempDir.resolve("nonexistent");

        assertThrows(MojoExecutionException.class,
                mojoWithExportDir(configFile, nonExistentDir)::execute);
    }
}
