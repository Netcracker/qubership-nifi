package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "id", List.of(), List.of(), null, false);
    }

    @Test
    void writeSerializesRootNodeToFile() throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("flowName", "test");
        Path file = tempDir.resolve("flow.json");
        FlowFile flow = new FlowFile(file, root, rootGroup(), Map.of());

        new FlowWriter(MAPPER).write(flow);

        JsonNode read = MAPPER.readTree(file.toFile());
        assertEquals("test", read.get("flowName").asText());
    }

    @Test
    void writeReflectsInPlacePropertyChanges() throws IOException {
        ObjectNode propsNode = MAPPER.createObjectNode();
        propsNode.put("SQL Query", "SELECT 1");
        ObjectNode flowContents = MAPPER.createObjectNode();
        flowContents.set("properties", propsNode);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("flowContents", flowContents);

        Path file = tempDir.resolve("flow.json");
        FlowFile flow = new FlowFile(file, root, rootGroup(), Map.of());

        propsNode.put("SQL Query", "SELECT 2");
        new FlowWriter(MAPPER).write(flow);

        JsonNode read = MAPPER.readTree(file.toFile());
        assertEquals("SELECT 2",
                read.get("flowContents").get("properties").get("SQL Query").asText());
    }

    @Test
    void writeOverwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("flow.json");

        ObjectNode root1 = MAPPER.createObjectNode();
        root1.put("version", "1");
        new FlowWriter(MAPPER).write(new FlowFile(file, root1, rootGroup(), Map.of()));

        ObjectNode root2 = MAPPER.createObjectNode();
        root2.put("version", "2");
        new FlowWriter(MAPPER).write(new FlowFile(file, root2, rootGroup(), Map.of()));

        JsonNode read = MAPPER.readTree(file.toFile());
        assertEquals("2", read.get("version").asText());
    }
}
