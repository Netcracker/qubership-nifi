package org.qubership.nifi.maven.flowdiff.report;

import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;

import java.util.List;

/**
 * The differences found in a single paired flow, together with its normalized display path. Technical differences are
 * retained here so their per-category count is available even though the renderers do not list them individually.
 */
public final class FlowReport {

    private final String path;
    private final List<Difference> changes;

    /**
     * Creates a flow report.
     *
     * @param pathValue    the normalized flow display path
     * @param changesValue the differences found in the flow
     */
    public FlowReport(final String pathValue, final List<Difference> changesValue) {
        this.path = pathValue;
        this.changes = List.copyOf(changesValue);
    }

    /**
     * Returns the normalized flow display path.
     *
     * @return the flow path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns all differences found in the flow, technical ones included.
     *
     * @return the immutable list of differences
     */
    public List<Difference> getChanges() {
        return changes;
    }

    /**
     * Counts the differences in the flow that fall into a category.
     *
     * @param category the category to count
     * @return the number of differences in that category
     */
    public long count(final ChangeCategory category) {
        return changes.stream().filter(difference -> difference.getCategory() == category).count();
    }
}
