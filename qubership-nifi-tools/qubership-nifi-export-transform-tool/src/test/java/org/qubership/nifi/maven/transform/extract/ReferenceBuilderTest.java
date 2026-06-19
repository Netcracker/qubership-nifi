package org.qubership.nifi.maven.transform.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.ProcessGroup;
import org.qubership.nifi.maven.transform.flow.Processor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReferenceBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";
    private final ReferenceBuilder builder = new ReferenceBuilder();

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private FlowFile flowFile(Path path, Processor processor) {
        return new FlowFile(path, MAPPER.createObjectNode(), rootGroup(),
                Map.of(TYPE, List.of(processor)));
    }

    @Test
    void buildReferenceForProcessorInRootGroupUsesForwardSlashes() {
        ProcessGroup root = rootGroup();
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), root);
        FlowFile flow = flowFile(Path.of("exports", "my-flow.json"), processor);

        String ref = builder.buildReference(flow, processor, "query.sql");

        assertEquals("@flowConf_my-flow/MyProcessor/query.sql", ref);
    }

    @Test
    void buildReferenceForProcessorInNestedGroupIncludesGroupPath() {
        ProcessGroup root = rootGroup();
        ProcessGroup group = new ProcessGroup("Extract", "g-id", List.of(), List.of(), root, false);
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), group);
        FlowFile flow = flowFile(Path.of("exports", "my-flow.json"), processor);

        String ref = builder.buildReference(flow, processor, "query.sql");

        assertEquals("@flowConf_my-flow/Extract/MyProcessor/query.sql", ref);
    }

    @Test
    void buildReferenceForDeeplyNestedProcessorIncludesAllGroupSegments() {
        ProcessGroup root = rootGroup();
        ProcessGroup outer = new ProcessGroup("Outer", "o-id", List.of(), List.of(), root, false);
        ProcessGroup inner = new ProcessGroup("Inner", "i-id", List.of(), List.of(), outer, false);
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), inner);
        FlowFile flow = flowFile(Path.of("exports", "flow.json"), processor);

        String ref = builder.buildReference(flow, processor, "script.groovy");

        assertEquals("@flowConf_flow/Outer/Inner/MyProcessor/script.groovy", ref);
    }

    @Test
    void buildReferenceNeverContainsBackslash() {
        ProcessGroup root = rootGroup();
        ProcessGroup group = new ProcessGroup("GroupA", "g-id", List.of(), List.of(), root, false);
        Processor processor = new Processor("ProcB", TYPE, "id", MAPPER.createObjectNode(), group);
        FlowFile flow = flowFile(Path.of("exports", "flow.json"), processor);

        String ref = builder.buildReference(flow, processor, "file.sql");

        assertFalse(ref.contains("\\"), "Reference must use forward slashes on all platforms");
    }

    @Test
    void buildAbsoluteFilePathForProcessorInRootGroup() {
        ProcessGroup root = rootGroup();
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), root);
        Path flowPath = Path.of("exports", "my-flow.json");
        FlowFile flow = flowFile(flowPath, processor);

        Path result = builder.buildAbsoluteFilePath(flow, processor, "query.sql");

        Path expected = Path.of("exports", "flowConf_my-flow", "MyProcessor", "query.sql");
        assertEquals(expected, result);
    }

    @Test
    void buildAbsoluteFilePathForProcessorInNestedGroup() {
        ProcessGroup root = rootGroup();
        ProcessGroup group = new ProcessGroup("Extract", "g-id", List.of(), List.of(), root, false);
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), group);
        Path flowPath = Path.of("exports", "my-flow.json");
        FlowFile flow = flowFile(flowPath, processor);

        Path result = builder.buildAbsoluteFilePath(flow, processor, "query.sql");

        Path expected = Path.of("exports", "flowConf_my-flow", "Extract", "MyProcessor", "query.sql");
        assertEquals(expected, result);
    }
}
