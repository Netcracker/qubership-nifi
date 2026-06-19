package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private FlowFile flowFile(Path path, Map<String, List<Processor>> byType) {
        return new FlowFile(path, MAPPER.createObjectNode(), rootGroup(), byType);
    }

    @Test
    void getFlowNameStripsExtension() {
        FlowFile flow = flowFile(Path.of("my-flow.json"), Map.of());
        assertEquals("my-flow", flow.getFlowName());
    }

    @Test
    void getFlowNameHandlesFileWithNoExtension() {
        FlowFile flow = flowFile(Path.of("myflow"), Map.of());
        assertEquals("myflow", flow.getFlowName());
    }

    @Test
    void getProcessorsByTypeReturnsMatchingProcessors() {
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowFile(Path.of("flow.json"), Map.of(TYPE, List.of(p)));

        List<Processor> result = flow.getProcessorsByType(TYPE);

        assertEquals(1, result.size());
        assertEquals("MyProcessor", result.get(0).getName());
    }

    @Test
    void getProcessorsByTypeReturnsEmptyListForUnknownType() {
        FlowFile flow = flowFile(Path.of("flow.json"), Map.of());
        assertTrue(flow.getProcessorsByType("org.unknown.Type").isEmpty());
    }

    @Test
    void getFilePathReturnsOriginalPath() {
        Path path = Path.of("dir", "flow.json");
        FlowFile flow = flowFile(path, Map.of());
        assertEquals(path, flow.getFilePath());
    }
}
