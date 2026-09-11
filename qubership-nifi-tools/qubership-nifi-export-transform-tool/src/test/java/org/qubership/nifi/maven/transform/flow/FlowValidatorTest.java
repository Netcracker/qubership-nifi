package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FlowValidatorTest {

    private static final String TYPE = "org.apache.nifi.processors.standard.ExecuteSQL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mock Maven logger. */
    @Mock
    private Log log;

    private FlowValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FlowValidator(log);
    }

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
    void validateDisambiguatesDuplicateProcessorPathsInsteadOfReportingAnError() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE, "id-111111111111", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-222222222222", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
        assertNotEquals(p1.getRelativePath(), p2.getRelativePath());
        verify(log).info(anyString());
    }

    @Test
    void validateLogsNothingWhenNoProcessorNamesCollide() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("Alpha", TYPE, "id-1", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("Beta", TYPE, "id-2", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        validator.validate(flow, config(PropertyMapping.of("SQL Query", "query.sql")));

        verify(log, never()).info(anyString());
    }

    @Test
    void validateNoErrorWhenSameNameProcessorsAreInDifferentChildGroups() {
        ProcessGroup root = rootGroup();
        ProcessGroup groupA = new ProcessGroup("GroupA", "gid-a", List.of(), List.of(), root, false);
        ProcessGroup groupB = new ProcessGroup("GroupB", "gid-b", List.of(), List.of(), root, false);
        Processor p1 = new Processor("MyProcessor", TYPE, "id1", MAPPER.createObjectNode(), groupA);
        Processor p2 = new Processor("MyProcessor", TYPE, "id2", MAPPER.createObjectNode(), groupB);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateDisambiguatesProcessorsInChildGroupsWithSameName() {
        ProcessGroup root = rootGroup();
        ProcessGroup group1 = new ProcessGroup("SameGroup", "gid1", List.of(), List.of(), root, false);
        ProcessGroup group2 = new ProcessGroup("SameGroup", "gid2", List.of(), List.of(), root, false);
        Processor p1 = new Processor("MyProcessor", TYPE, "id-111111111111", MAPPER.createObjectNode(), group1);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-222222222222", MAPPER.createObjectNode(), group2);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
        assertNotEquals(p1.getRelativePath(), p2.getRelativePath());
    }

    @Test
    void validateNoErrorWhenParentGroupNamesMatchButChildGroupNamesDiffer() {
        ProcessGroup root = rootGroup();
        ProcessGroup parent1 = new ProcessGroup("group1", "gid1", List.of(), List.of(), root, false);
        ProcessGroup parent2 = new ProcessGroup("group1", "gid2", List.of(), List.of(), root, false);
        ProcessGroup child1 = new ProcessGroup("group11", "gid11", List.of(), List.of(), parent1, false);
        ProcessGroup child2 = new ProcessGroup("group12", "gid22", List.of(), List.of(), parent2, false);
        Processor p1 = new Processor("MyProcessor", TYPE, "id1", MAPPER.createObjectNode(), child1);
        Processor p2 = new Processor("MyProcessor", TYPE, "id2", MAPPER.createObjectNode(), child2);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateDisambiguatesGroupsThatClashAfterEncoding() {
        ProcessGroup root = rootGroup();
        ProcessGroup withSpecialChar = new ProcessGroup("Filter status>0", "g-1",
                List.of(), List.of(), root, false);
        ProcessGroup withToken = new ProcessGroup("Filter status_gt_0", "g-2",
                List.of(), List.of(), root, false);
        Processor p1 = new Processor("P", TYPE, "id-111111111111", MAPPER.createObjectNode(), withSpecialChar);
        Processor p2 = new Processor("P", TYPE, "id-222222222222", MAPPER.createObjectNode(), withToken);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
        assertNotEquals(p1.getRelativePath(), p2.getRelativePath());
    }

    @Test
    void validateDisambiguatesProcessorsThatClashAfterEncoding() {
        ProcessGroup root = rootGroup();
        Processor withSpecialChar = new Processor("Filter a>b", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), root);
        Processor withToken = new Processor("Filter a_gt_b", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(withSpecialChar, withToken)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
        assertNotEquals(withSpecialChar.getRelativePath(), withToken.getRelativePath());
    }

    @Test
    void validateNoErrorWhenEncodedGroupNamesRemainDistinct() {
        ProcessGroup root = rootGroup();
        ProcessGroup withSpecialChar = new ProcessGroup("Filter status > 0", "g-1",
                List.of(), List.of(), root, false);
        ProcessGroup withPlainText = new ProcessGroup("Filter status gt 0", "g-2",
                List.of(), List.of(), root, false);
        Processor p1 = new Processor("P", TYPE, "id-1", MAPPER.createObjectNode(), withSpecialChar);
        Processor p2 = new Processor("P", TYPE, "id-2", MAPPER.createObjectNode(), withPlainText);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateAcceptsSpecialCharsInProcessorName() {
        ProcessGroup root = rootGroup();
        Processor p = new Processor("My*Processor", TYPE, "id", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateAcceptsSpecialCharsInProcessGroupName() {
        ProcessGroup root = rootGroup();
        ProcessGroup group = new ProcessGroup("group/name", "g-id",
                List.of(), List.of(), root, false);
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), group);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertTrue(errors.isEmpty());
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
                config(PropertyMapping.ofRegex("Script.*", "script.groovy")));

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
                config(PropertyMapping.ofRegex("Script.*", "script.groovy")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void validateReturnsErrorWhenDisambiguatingSuffixesStillCollide() {
        // Identifiers differ, but share the same last 12 characters, so the disambiguating
        // suffix does not separate them either.
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE,
                "11111111-1111-1111-1111-999999999999", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE,
                "22222222-2222-2222-2222-999999999999", MAPPER.createObjectNode(), root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql")));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Duplicate"));
        assertTrue(errors.get(0).contains("MyProcessor"));
    }

    @Test
    void validateCollectsAllErrorsInSingleRun() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE,
                "11111111-1111-1111-1111-999999999999", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE,
                "22222222-2222-2222-2222-999999999999", MAPPER.createObjectNode(), root);
        ObjectNode scriptProps = MAPPER.createObjectNode();
        scriptProps.put("Script Body", "println 'hi'");
        scriptProps.put("Script File", "script.groovy");
        Processor scripted = new Processor("Scripted", TYPE, "id-3", scriptProps, root);
        FlowFile flow = new FlowFile(Path.of("flow.json"), MAPPER.createObjectNode(),
                root, Map.of(TYPE, List.of(p1, p2, scripted)));

        List<String> errors = validator.validate(flow,
                config(PropertyMapping.of("SQL Query", "query.sql"),
                        PropertyMapping.ofRegex("Script.*", "script.groovy")));

        assertEquals(2, errors.size());
    }
}
