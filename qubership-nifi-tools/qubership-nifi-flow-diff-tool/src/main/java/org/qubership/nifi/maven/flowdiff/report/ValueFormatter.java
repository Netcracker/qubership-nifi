package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders a raw value on a single logical line for the human formats: control characters are escaped to their
 * backslash forms and the result is truncated to a budget with a {@code ...(+N chars)} marker naming the number of
 * characters dropped. Escaping and truncation are applied only here; the JSON report keeps the full raw value.
 */
public final class ValueFormatter {

    private static final String ABSENT = "(absent)";

    private ValueFormatter() {
    }

    /**
     * Renders a value node to display text, escaped and truncated to the given budget.
     *
     * @param node      the value node, or {@code null} when the field is absent on that side
     * @param maxLength the truncation budget in characters; {@code 0} disables truncation
     * @return the escaped, truncated display text
     */
    public static String format(final JsonNode node, final int maxLength) {
        return truncate(escape(render(node)), maxLength);
    }

    private static String render(final JsonNode node) {
        if (node == null) {
            return ABSENT;
        }
        if (node.isNull()) {
            return "null";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String escape(final String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncate(final String value, final int maxLength) {
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        int dropped = value.length() - maxLength;
        return value.substring(0, maxLength) + "...(+" + dropped + " chars)";
    }
}
