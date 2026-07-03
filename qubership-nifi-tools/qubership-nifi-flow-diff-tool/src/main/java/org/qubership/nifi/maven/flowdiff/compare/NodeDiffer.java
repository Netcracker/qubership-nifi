package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Computes the leaf differences between two component nodes. Objects are compared key by key; a differing array is
 * reported as a single leaf difference rather than by unstable index. Most arrays are compared by value, so element
 * order is significant; the relationship arrays in {@link #UNORDERED_ARRAY_FIELDS} are an exception and are compared
 * ignoring order, because NiFi treats their values as an unordered set. Child-component collections and
 * {@code propertyDescriptors} are excluded because they are compared through component matching, not as raw fields.
 */
public final class NodeDiffer {

    private static final String PROPERTY_DESCRIPTORS = "propertyDescriptors";

    /**
     * String-array fields whose value order carries no meaning in NiFi and can be shuffled by reformatting or
     * reconfiguration. They are compared ignoring order so a pure reorder is not reported as a difference.
     */
    private static final Set<String> UNORDERED_ARRAY_FIELDS = Set.of(
            "selectedRelationships", "autoTerminatedRelationships", "retriedRelationships");

    private final Set<String> excludedTopLevel;

    /**
     * Creates the NodeDiffer that excludes the given top-level keys of the compared component nodes.
     *
     * @param excludedTopLevelKeys the top-level field names to skip (child collections for a group)
     */
    public NodeDiffer(final Set<String> excludedTopLevelKeys) {
        this.excludedTopLevel = excludedTopLevelKeys;
    }

    /**
     * Computes the leaf differences between two component nodes.
     *
     * @param baseline the baseline component node
     * @param target   the target component node
     * @return the leaf differences, in deterministic key order
     */
    public List<LeafDiff> diff(final JsonNode baseline, final JsonNode target) {
        List<LeafDiff> out = new ArrayList<>();
        walk(baseline, target, new ArrayList<>(), out);
        return out;
    }

    private void walk(final JsonNode baseline, final JsonNode target, final List<String> relPath,
            final List<LeafDiff> out) {
        if (baseline != null && target != null && baseline.isObject() && target.isObject()) {
            for (String key : JsonNodeUtils.getUniqueSortedFieldNames(baseline, target)) {
                if (isExcluded(relPath, key)) {
                    continue;
                }
                relPath.add(key);
                walk(baseline.get(key), target.get(key), relPath, out);
                relPath.remove(relPath.size() - 1);
            }
            return;
        }
        if (!leafEquals(baseline, target, relPath)) {
            out.add(new LeafDiff(List.copyOf(relPath), baseline, target));
        }
    }

    private static boolean leafEquals(final JsonNode baseline, final JsonNode target, final List<String> relPath) {
        if (!relPath.isEmpty() && UNORDERED_ARRAY_FIELDS.contains(relPath.get(relPath.size() - 1))) {
            return arraysEqualIgnoringOrder(baseline, target);
        }
        return nodeEquals(baseline, target);
    }

    private boolean isExcluded(final List<String> relPath, final String key) {
        if (PROPERTY_DESCRIPTORS.equals(key)) {
            return true;
        }
        return relPath.isEmpty() && excludedTopLevel.contains(key);
    }

    private static boolean nodeEquals(final JsonNode baseline, final JsonNode target) {
        if (baseline == null && target == null) {
            return true;
        }
        if (baseline == null || target == null) {
            return false;
        }
        return baseline.equals(target);
    }

    private static boolean arraysEqualIgnoringOrder(final JsonNode baseline, final JsonNode target) {
        if (baseline == null || target == null || !baseline.isArray() || !target.isArray()) {
            return nodeEquals(baseline, target);
        }
        if (baseline.size() != target.size()) {
            return false;
        }
        List<String> baselineElements = new ArrayList<>();
        List<String> targetElements = new ArrayList<>();
        baseline.forEach(element -> baselineElements.add(element.toString()));
        target.forEach(element -> targetElements.add(element.toString()));
        Collections.sort(baselineElements);
        Collections.sort(targetElements);
        return baselineElements.equals(targetElements);
    }
}
