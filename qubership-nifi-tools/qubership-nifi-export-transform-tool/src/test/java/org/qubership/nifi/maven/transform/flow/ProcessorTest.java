package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    @Test
    void getFullPathReturnsOnlyProcessorNameInRootGroup() {
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        assertEquals("MyProcessor", p.getFullPath());
    }

    @Test
    void getFullPathIncludesParentGroupSegments() {
        ProcessGroup root = rootGroup();
        ProcessGroup child = new ProcessGroup("Extract", "child-id", List.of(), List.of(), root, false);
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), child);
        assertEquals("Extract / MyProcessor", p.getFullPath());
    }

    @Test
    void getRelativePathReturnsOnlyProcessorNameInRootGroup() {
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        assertEquals(Path.of("MyProcessor"), p.getRelativePath());
    }

    @Test
    void getRelativePathIncludesParentGroupPath() {
        ProcessGroup root = rootGroup();
        ProcessGroup child = new ProcessGroup("Extract", "child-id", List.of(), List.of(), root, false);
        Processor p = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), child);
        assertEquals(Path.of("Extract", "MyProcessor"), p.getRelativePath());
    }

    @Test
    void findPropertyReturnsPropertyWhenPresent() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "SELECT 1");
        Processor p = new Processor("P", TYPE, "id", props, rootGroup());

        var result = p.findProperty("SQL Query");

        assertTrue(result.isPresent());
        assertEquals("SELECT 1", result.get().getValue());
    }

    @Test
    void findPropertyReturnsEmptyWhenPropertyNotPresent() {
        Processor p = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        assertTrue(p.findProperty("SQL Query").isEmpty());
    }

    @Test
    void findPropertiesByRegexReturnsAllMatchingProperties() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("Script Body", "println 'hi'");
        props.put("Script File", "script.groovy");
        props.put("Other Property", "value");
        Processor p = new Processor("P", TYPE, "id", props, rootGroup());

        List<ProcessorProperty> matches = p.findPropertiesByRegex(Pattern.compile("Script.*"));

        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> m.getName().equals("Script Body")));
        assertTrue(matches.stream().anyMatch(m -> m.getName().equals("Script File")));
    }

    @Test
    void findPropertiesByRegexReturnsEmptyWhenNoPropertiesMatch() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "SELECT 1");
        Processor p = new Processor("P", TYPE, "id", props, rootGroup());

        assertTrue(p.findPropertiesByRegex(Pattern.compile("Script.*")).isEmpty());
    }

    @Test
    void findPropertiesByRegexReturnsSingleExactMatch() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "SELECT 1");
        Processor p = new Processor("P", TYPE, "id", props, rootGroup());

        List<ProcessorProperty> matches = p.findPropertiesByRegex(Pattern.compile("SQL Query"));

        assertEquals(1, matches.size());
        assertEquals("SQL Query", matches.get(0).getName());
    }
}
