package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A raw leaf difference between two component nodes: the field path relative to the component and the differing
 * baseline and target values. Either value may be {@code null} when the field is present on one side only.
 *
 * @param relPath  the field path relative to the owning component, as unescaped segments
 * @param baseline the baseline value, or {@code null} when absent
 * @param target   the target value, or {@code null} when absent
 */
public record LeafDiff(List<String> relPath, JsonNode baseline, JsonNode target) {
}
