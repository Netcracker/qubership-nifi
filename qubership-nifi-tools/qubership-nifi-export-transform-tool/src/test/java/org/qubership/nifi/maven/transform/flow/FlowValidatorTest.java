package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowValidatorTest {

    private static final String TYPE = "org.apache.nifi.processors.standard.ExecuteSQL";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FlowValidator validator = new FlowValidator();

    private PluginConfig config(PropertyMapping... mappings) {
        return new PluginConfig(List.of(new ProcessorTypeConfig(TYPE, List.of(mappings))));
    }

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private FlowFile flowFile(List<Processor> processors) {
        Map<String, List<Processor>> byType = processors.isEmpty()
                ? Map.of() : Map.of(TYPE, processors);
        return new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(), rootGroup(), byType);
    }

    @Test
    void validateReturnsEmptyListForValidFlow() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "SELECT 1");
        Processor p = new Processor("MyProcessor", TYPE, "id", props, rootGroup());

        List<String> errors = validator.validate(
                flowFile(List.of(p)),
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateReturnsEmptyListWhenNoProcessorsOfConfiguredType() {
        List<String> errors = validator.validate(
                flowFile(List.of()),
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateReturnsErrorForDuplicateProcessorPaths() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE, "id-1", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-2", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Duplicate"));
        assertTrue(errors.get(0).contains("MyProcessor"));
    }

    @Test
    void validateReturnsErrorForInvalidCharsInProcessorName() {
        ProcessGroup root = rootGroup();
        Processor p = new Processor("My*Processor", TYPE, "id", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("processor name"));
        assertTrue(errors.get(0).contains("My*Processor"));
    }

    @Test
    void validateReturnsErrorForInvalidCharsInProcessGroupName() {
        ProcessGroup root = rootGroup();
        ProcessGroup invalid = new ProcessGroup("group/name", "g-id",
                List.of(), List.of(), root, false);
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), invalid);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("process group name"));
        assertTrue(errors.get(0).contains("group/name"));
    }

    @Test
    void validateReturnsErrorForAmbiguousRegexMapping() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("Script Body", "println 'hi'");
        props.put("Script File", "script.groovy");
        ProcessGroup root = rootGroup();
        Processor p = new Processor("MyProcessor", TYPE, "id", props, root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("Script.*", "script.groovy")));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Script.*"));
        assertTrue(errors.get(0).contains("multiple properties"));
    }

    @Test
    void validateNoErrorWhenRegexMatchesExactlyOneProperty() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("Script Body", "println 'hi'");
        ProcessGroup root = rootGroup();
        Processor p = new Processor("MyProcessor", TYPE, "id", props, root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("Script.*", "script.groovy")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateCollectsAllErrorsInSingleRun() {
        ProcessGroup root = rootGroup();
        Processor dup1 = new Processor("SameName", TYPE, "id-1", MAPPER.createObjectNode(), root);
        Processor dup2 = new Processor("SameName", TYPE, "id-2", MAPPER.createObjectNode(), root);
        Processor invalid = new Processor("Bad*Name", TYPE, "id-3", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(dup1, dup2, invalid)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertEquals(2, errors.size());
    }
}
