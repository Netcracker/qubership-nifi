package org.qubership.nifi.maven.flowdiff.flow;

/**
 * A reference to an ancestor process group used when building a component's canonical path. The canonical form labels
 * the root by name and a nested group as {@code name(identifier)}; {@code needsId} additionally tells the human
 * renderers whether a sibling group shares this name, so they append a shortened identifier only when required.
 *
 * @param name       the group name (may be empty)
 * @param identifier the group identifier
 * @param root       whether this reference is the root process group
 * @param needsId    whether a sibling group of the parent shares this name, requiring an identifier for disambiguation
 */
public record GroupRef(String name, String identifier, boolean root, boolean needsId) {
}
