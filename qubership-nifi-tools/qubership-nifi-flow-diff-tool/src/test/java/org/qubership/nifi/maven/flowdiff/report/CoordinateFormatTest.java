package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link CoordinateFormat}: a {@code position} object renders as an {@code (x, y)} pair, a {@code bends}
 * array renders as a bracketed list of pairs, and absent values render as {@code (absent)}.
 */
class CoordinateFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode node(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void formatsPositionAsPair() {
        assertEquals("(464.0, -96.0)", CoordinateFormat.pair(node("{\"x\":464.0,\"y\":-96.0}")));
    }

    @Test
    void formatsAbsentPositionAsAbsent() {
        assertEquals("(absent)", CoordinateFormat.pair(null));
    }

    @Test
    void formatsBendsAsPairList() {
        assertEquals("[(976.0, 64.0), (975.0, 63.0)]",
                CoordinateFormat.bends(node("[{\"x\":976.0,\"y\":64.0},{\"x\":975.0,\"y\":63.0}]")));
    }

    @Test
    void formatsEmptyBendsAsEmptyList() {
        assertEquals("[]", CoordinateFormat.bends(node("[]")));
    }

    @Test
    void formatsAbsentBendsAsAbsent() {
        assertEquals("(absent)", CoordinateFormat.bends(null));
    }
}
