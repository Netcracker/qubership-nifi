package org.qubership.nifi.maven.flowdiff.revert;

/**
 * The count of reverted technical changes in a single flow, broken down by kind for the per-file summary line.
 *
 * @param instanceIdentifier the number of {@code instanceIdentifier} values restored on components and connection
 *                           endpoints
 * @param rootIdentifier     the number of root process-group {@code identifier} values restored (0 or 1)
 * @param groupIdentifier    the number of direct-child {@code groupIdentifier} back-references restored
 */
public record RevertCounts(int instanceIdentifier, int rootIdentifier, int groupIdentifier) {

    /**
     * Returns the total number of reverted technical changes.
     *
     * @return the sum across all kinds
     */
    public int total() {
        return instanceIdentifier + rootIdentifier + groupIdentifier;
    }
}
