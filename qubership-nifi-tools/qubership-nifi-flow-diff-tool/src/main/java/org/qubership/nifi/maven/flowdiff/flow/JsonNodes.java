package org.qubership.nifi.maven.flowdiff.flow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small null-safe helpers for reading text out of Jackson {@link JsonNode} trees. Shared by the comparison and revert
 * code so a missing field and an explicit JSON {@code null} are treated identically everywhere.
 */
public final class JsonNodes {

    private JsonNodes() {
    }

    /**
     * Reads a field as text, treating a missing field and an explicit JSON {@code null} the same.
     *
     * @param node  the containing object node
     * @param field the field name
     * @return the field's text value, or {@code null} when the field is absent or JSON {@code null}
     */
    public static String text(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Reads a field as text, defaulting to the empty string, treating a missing field and an explicit JSON
     * {@code null} the same.
     *
     * @param node  the containing object node
     * @param field the field name
     * @return the field's text value, or {@code ""} when the field is absent or JSON {@code null}
     */
    public static String textOrEmpty(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /**
     * Reads a node as text, treating a Java {@code null} node and an explicit JSON {@code null} the same.
     *
     * @param node the node
     * @return the node's text value, or {@code null} when the node is {@code null} or JSON {@code null}
     */
    public static String asText(final JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Tells whether a string is {@code null} or empty.
     *
     * @param value the string
     * @return {@code true} when the value is {@code null} or has length zero
     */
    public static boolean isEmpty(final String value) {
        return value == null || value.isEmpty();
    }
}
