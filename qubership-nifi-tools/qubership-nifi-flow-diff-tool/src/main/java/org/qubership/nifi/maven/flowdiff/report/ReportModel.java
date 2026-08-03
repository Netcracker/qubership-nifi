package org.qubership.nifi.maven.flowdiff.report;

import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;

import java.util.List;

/**
 * The full in-memory diff model shared by every renderer: the per-flow reports plus the whole added and removed flow
 * files. Whole-flow additions and removals live here rather than inside a flow's change list, so they are never folded
 * into a flow's significant count and every renderer consumes them the same way.
 */
public final class ReportModel {

    private final List<FlowReport> flows;
    private final List<String> addedFlows;
    private final List<String> removedFlows;

    /**
     * Creates a report model.
     *
     * @param flowsValue        the per-flow reports
     * @param addedFlowsValue   normalized paths of flows present only on the target side
     * @param removedFlowsValue normalized paths of flows present only on the baseline side
     */
    public ReportModel(final List<FlowReport> flowsValue, final List<String> addedFlowsValue,
            final List<String> removedFlowsValue) {
        this.flows = List.copyOf(flowsValue);
        this.addedFlows = List.copyOf(addedFlowsValue);
        this.removedFlows = List.copyOf(removedFlowsValue);
    }

    /**
     * Returns the per-flow reports.
     *
     * @return the immutable list of flow reports
     */
    public List<FlowReport> getFlows() {
        return flows;
    }

    /**
     * Returns the normalized paths of whole added flows.
     *
     * @return the immutable added-flow paths
     */
    public List<String> getAddedFlows() {
        return addedFlows;
    }

    /**
     * Returns the normalized paths of whole removed flows.
     *
     * @return the immutable removed-flow paths
     */
    public List<String> getRemovedFlows() {
        return removedFlows;
    }

    /**
     * Sums a category across every flow report.
     *
     * @param category the category to total
     * @return the combined count across all flows
     */
    public long total(final ChangeCategory category) {
        return flows.stream().mapToLong(flow -> flow.count(category)).sum();
    }
}
