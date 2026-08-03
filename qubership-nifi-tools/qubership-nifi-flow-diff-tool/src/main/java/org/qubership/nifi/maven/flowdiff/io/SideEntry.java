package org.qubership.nifi.maven.flowdiff.io;

import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

/**
 * A discovered candidate on one side (baseline or target) at a relative path: its classification, normalized display
 * path, and, for a flow export, the parsed {@link FlowExport}.
 */
public final class SideEntry {

    private final CandidateKind kind;
    private final String displayPath;
    private final FlowExport flow;

    private SideEntry(final CandidateKind kindValue, final String displayPathValue, final FlowExport flowValue) {
        this.kind = kindValue;
        this.displayPath = displayPathValue;
        this.flow = flowValue;
    }

    /**
     * Creates a flow entry.
     *
     * @param displayPathValue the normalized display path
     * @param flowValue        the parsed flow export
     * @return a flow entry
     */
    public static SideEntry flow(final String displayPathValue, final FlowExport flowValue) {
        return new SideEntry(CandidateKind.FLOW, displayPathValue, flowValue);
    }

    /**
     * Creates a non-flow entry.
     *
     * @param displayPathValue the normalized display path
     * @return a non-flow entry
     */
    public static SideEntry nonFlow(final String displayPathValue) {
        return new SideEntry(CandidateKind.NON_FLOW, displayPathValue, null);
    }

    /**
     * Returns the candidate classification.
     *
     * @return the kind
     */
    public CandidateKind getKind() {
        return kind;
    }

    /**
     * Returns the normalized display path.
     *
     * @return the display path
     */
    public String getDisplayPath() {
        return displayPath;
    }

    /**
     * Returns the parsed flow export, or {@code null} for a non-flow entry.
     *
     * @return the flow export or {@code null}
     */
    public FlowExport getFlow() {
        return flow;
    }

    /**
     * Tells whether this entry is a flow export.
     *
     * @return {@code true} for a flow export
     */
    public boolean isFlow() {
        return kind == CandidateKind.FLOW;
    }
}
