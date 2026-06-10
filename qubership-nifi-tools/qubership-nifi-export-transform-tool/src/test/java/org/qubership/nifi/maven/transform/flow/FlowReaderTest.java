package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowReaderTest {

    private static final String TYPE = "org.qubership.nifi.TestProcessor";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private FlowReader reader;

    @BeforeEach
    void setUp() {
        PluginConfig config = new PluginConfig(List.of(
                new ProcessorTypeConfig(TYPE,
                        List.of(PropertyMapping.of("SQL Query", "query.sql")))));
        reader = new FlowReader(MAPPER, config);
    }

    private Path writeFlow(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    private String flowWithProcessor(String processorName, String type) {
        return """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [{
                      "name": "%s", "type": "%s", "identifier": "proc-id",
                      "properties": {"SQL Query": "SELECT 1"}
                    }],
                    "processGroups": []
                  }
                }
                """.formatted(processorName, type);
    }

    @Test
    void readBuildsProcessorsByTypeMapForConfiguredType() throws IOException {
        Path file = writeFlow("flow.json", flowWithProcessor("MyProcessor", TYPE));

        FlowFile flow = reader.read(file);

        List<Processor> processors = flow.getProcessorsByType(TYPE);
        assertEquals(1, processors.size());
        assertEquals("MyProcessor", processors.get(0).getName());
    }

    @Test
    void readIgnoresProcessorsOfUnconfiguredType() throws IOException {
        Path file = writeFlow("flow.json", flowWithProcessor("OtherProcessor", "org.other.Type"));

        FlowFile flow = reader.read(file);

        assertTrue(flow.getProcessorsByType("org.other.Type").isEmpty());
    }

    @Test
    void readSetsFilePathAndFlowName() throws IOException {
        Path file = writeFlow("my-flow.json", flowWithProcessor("P", TYPE));

        FlowFile flow = reader.read(file);

        assertEquals(file, flow.getFilePath());
        assertEquals("my-flow", flow.getFlowName());
    }

    @Test
    void readParsesProcessorInNestedProcessGroup() throws IOException {
        String json = """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [],
                    "processGroups": [{
                      "name": "Extract", "identifier": "group-id",
                      "processors": [{
                        "name": "MyProcessor", "type": "%s", "identifier": "proc-id",
                        "properties": {"SQL Query": "SELECT 1"}
                      }],
                      "processGroups": []
                    }]
                  }
                }
                """.formatted(TYPE);
        Path file = writeFlow("flow.json", json);

        FlowFile flow = reader.read(file);

        List<Processor> processors = flow.getProcessorsByType(TYPE);
        assertEquals(1, processors.size());
        assertEquals("Extract", processors.get(0).getParentGroup().getName());
    }

    @Test
    void readCreatesEmptyPropertiesNodeWhenAbsent() throws IOException {
        String json = """
                {
                  "flowContents": {
                    "name": "root", "identifier": "root-id",
                    "processors": [{"name": "P", "type": "%s", "identifier": "id"}],
                    "processGroups": []
                  }
                }
                """.formatted(TYPE);
        Path file = writeFlow("flow.json", json);

        FlowFile flow = reader.read(file);

        Processor p = flow.getProcessorsByType(TYPE).get(0);
        assertTrue(p.findProperty("anything").isEmpty());
    }

    @Test
    void readThrowsWhenFlowContentsMissing() throws IOException {
        Path file = writeFlow("flow.json", "{\"other\": \"value\"}");

        assertThrows(IllegalArgumentException.class, () -> reader.read(file));
    }

    @Test
    void findFlowPathsReturnsJsonFilesInDirectory() throws IOException {
        writeFlow("flow1.json", "{}");
        writeFlow("flow2.json", "{}");

        List<Path> paths = reader.findFlowPaths(tempDir);

        assertEquals(2, paths.size());
    }

    @Test
    void findFlowPathsSkipsFlowConfDirectories() throws IOException {
        writeFlow("flow.json", "{}");
        Path flowConfDir = tempDir.resolve("flowConf_myflow");
        Files.createDirectories(flowConfDir);
        Files.writeString(flowConfDir.resolve("nested.json"), "{}");

        List<Path> paths = reader.findFlowPaths(tempDir);

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).getFileName().toString().equals("flow.json"));
    }

    @Test
    void findFlowPathsSkipsNonJsonFiles() throws IOException {
        writeFlow("flow.json", "{}");
        writeFlow("flow.xml", "<root/>");
        writeFlow("notes.txt", "notes");

        List<Path> paths = reader.findFlowPaths(tempDir);

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).getFileName().toString().equals("flow.json"));
    }
}
