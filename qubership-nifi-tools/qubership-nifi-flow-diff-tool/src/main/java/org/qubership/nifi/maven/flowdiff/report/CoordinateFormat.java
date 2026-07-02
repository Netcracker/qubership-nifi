package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Formats NiFi coordinate structures for the human reports. A component {@code position} is a fixed {@code {x, y}}
 * object and connection {@code bends} are an array of such objects; the {@code x}/{@code y} keys are always implied, so
 * they are dropped in favor of {@code (x, y)} pairs. An absent value renders as {@code (absent)}, matching
 * {@link ValueFormatter}.
 */
public final class CoordinateFormat {

    private static final String ABSENT = "(absent)";
    private static final String X = "x";
    private static final String Y = "y";

    private CoordinateFormat() {
    }

    /**
     * Formats a single {@code {x, y}} object as {@code (x, y)}.
     *
     * @param position the position object, or {@code null} when absent
     * @return the formatted pair, or {@code (absent)} when the object is absent
     */
    public static String pair(final JsonNode position) {
        if (position == null || position.isNull() || !position.isObject()) {
            return ABSENT;
        }
        return "(" + coordinate(position.get(X)) + ", " + coordinate(position.get(Y)) + ")";
    }

    /**
     * Formats a {@code bends} array as {@code [(x1, y1), (x2, y2)]}.
     *
     * @param bends the bends array, or {@code null} when absent
     * @return the formatted list, or {@code (absent)} when the array is absent
     */
    public static String bends(final JsonNode bends) {
        if (bends == null || bends.isNull() || !bends.isArray()) {
            return ABSENT;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bends.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pair(bends.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String coordinate(final JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }
}
