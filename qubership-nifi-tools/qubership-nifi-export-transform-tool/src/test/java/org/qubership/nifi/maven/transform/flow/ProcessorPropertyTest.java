package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode props(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
        return node;
    }

    @Test
    void getNameReturnsName() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", MAPPER.createObjectNode());
        assertEquals("SQL Query", p.getName());
    }

    @Test
    void getValueReturnsStringValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", "SELECT 1"));
        assertEquals("SELECT 1", p.getValue());
    }

    @Test
    void getValueReturnsNullForJsonNullNode() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", null));
        assertNull(p.getValue());
    }

    @Test
    void getValueReturnsNullWhenPropertyAbsent() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", MAPPER.createObjectNode());
        assertNull(p.getValue());
    }

    @Test
    void setValueUpdatesJsonNodeInPlace() {
        ObjectNode propsNode = props("SQL Query", "SELECT 1");
        ProcessorProperty p = new ProcessorProperty("SQL Query", propsNode);

        p.setValue("SELECT 2");

        assertEquals("SELECT 2", p.getValue());
        assertEquals("SELECT 2", propsNode.get("SQL Query").asText());
    }

    @Test
    void isReferenceReturnsTrueWhenValueStartsWithAt() {
        ProcessorProperty p = new ProcessorProperty("SQL Query",
                props("SQL Query", "@flowConf_flow/path/query.sql"));
        assertTrue(p.isReference());
    }

    @Test
    void isReferenceReturnsFalseForRegularValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", "SELECT 1"));
        assertFalse(p.isReference());
    }

    @Test
    void isReferenceReturnsFalseForNullValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", null));
        assertFalse(p.isReference());
    }

    @Test
    void getReferencePathReturnsPathAfterAtSymbol() {
        ProcessorProperty p = new ProcessorProperty("SQL Query",
                props("SQL Query", "@flowConf_flow/path/query.sql"));
        assertEquals("flowConf_flow/path/query.sql", p.getReferencePath());
    }

    @Test
    void getReferencePathThrowsWhenPropertyIsNotReference() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", "SELECT 1"));
        assertThrows(IllegalStateException.class, p::getReferencePath);
    }

    @Test
    void isEmptyReturnsTrueForNullValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", null));
        assertTrue(p.isEmpty());
    }

    @Test
    void isEmptyReturnsTrueForBlankValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", "   "));
        assertTrue(p.isEmpty());
    }

    @Test
    void isEmptyReturnsFalseForNonBlankValue() {
        ProcessorProperty p = new ProcessorProperty("SQL Query", props("SQL Query", "SELECT 1"));
        assertFalse(p.isEmpty());
    }
}
