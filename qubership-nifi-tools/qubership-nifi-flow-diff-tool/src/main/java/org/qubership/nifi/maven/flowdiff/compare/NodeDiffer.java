package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the leaf differences between two component nodes. Objects are compared key by key; a differing array is
 * reported as a single leaf difference rather than by unstable index, matching the tool's decision to ignore array
 * reordering. Child-component collections and {@code propertyDescriptors} are excluded because they are compared
 * through component matching, not as raw fields.
 */
public final class NodeDiffer {

    private static final String PROPERTY_DESCRIPTORS = "propertyDescriptors";

    private final Set<String> excludedTopLevel;

    /**
     * Creates a differ that excludes the given top-level keys of the compared component nodes.
     *
     * @param excludedTopLevelKeys the top-level field names to skip (child collections for a group)
     */
    public NodeDiffer(final Set<String> excludedTopLevelKeys) {
        this.excludedTopLevel = Set.copyOf(excludedTopLevelKeys);
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
            for (String key : sortedKeys(baseline, target)) {
                if (isExcluded(relPath, key)) {
                    continue;
                }
                relPath.add(key);
                walk(baseline.get(key), target.get(key), relPath, out);
                relPath.remove(relPath.size() - 1);
            }
            return;
        }
        if (!nodeEquals(baseline, target)) {
            out.add(new LeafDiff(List.copyOf(relPath), baseline, target));
        }
    }

    private boolean isExcluded(final List<String> relPath, final String key) {
        if (PROPERTY_DESCRIPTORS.equals(key)) {
            return true;
        }
        return relPath.isEmpty() && excludedTopLevel.contains(key);
    }

    private static Set<String> sortedKeys(final JsonNode baseline, final JsonNode target) {
        Set<String> keys = new LinkedHashSet<>();
        baseline.fieldNames().forEachRemaining(keys::add);
        target.fieldNames().forEachRemaining(keys::add);
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        return new LinkedHashSet<>(sorted);
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
}
