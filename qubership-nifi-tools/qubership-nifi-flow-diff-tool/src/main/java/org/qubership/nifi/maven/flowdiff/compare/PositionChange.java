package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A snapshot of a component {@code position} object (its fixed {@code x} and {@code y} coordinates) on both sides,
 * attached to each {@code position/x} and {@code position/y} difference. It lets the text and Markdown renderers
 * collapse a move into a single {@code position: (x, y) -> (x, y)} line instead of one line per coordinate, and still
 * show both coordinates when only one of them changed. The differences remain separate so the counts and the JSON
 * report stay per-coordinate.
 *
 * @param baseline the {@code position} object on the baseline side, or {@code null} when absent
 * @param target   the {@code position} object on the target side, or {@code null} when absent
 */
public record PositionChange(JsonNode baseline, JsonNode target) {
}
