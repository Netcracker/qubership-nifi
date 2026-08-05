package org.qubership.nifi.flowdiff.service;

import org.qubership.nifi.flowdiff.revert.RevertCounts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What a revert run rewrote: the per-file counts for the files that changed, in the order they were processed, plus
 * the lines a front end prints. The lines live here rather than in each front end so the Maven plugin and the command
 * line report a run identically.
 */
public final class RevertSummary {

    private final Map<String, RevertCounts> counts;

    /**
     * Creates a summary.
     *
     * @param countsValue the per-file counts of the files that were rewritten, in processing order
     */
    public RevertSummary(final Map<String, RevertCounts> countsValue) {
        this.counts = Collections.unmodifiableMap(countsValue);
    }

    /**
     * Returns the per-file counts of the files that were rewritten, in processing order.
     *
     * @return the counts keyed by worktree-relative path
     */
    public Map<String, RevertCounts> getCounts() {
        return counts;
    }

    /**
     * Returns how many files were rewritten.
     *
     * @return the number of rewritten files
     */
    public int filesWritten() {
        return counts.size();
    }

    /**
     * Returns how many technical changes were reverted across all files.
     *
     * @return the total number of reverted changes
     */
    public int totalReverted() {
        return counts.values().stream().mapToInt(RevertCounts::total).sum();
    }

    /**
     * Returns one line per rewritten file, breaking the count down by field kind.
     *
     * @return the per-file summary lines, in processing order
     */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>(counts.size());
        for (Map.Entry<String, RevertCounts> entry : counts.entrySet()) {
            lines.add(summaryLine(entry.getKey(), entry.getValue()));
        }
        return lines;
    }

    /**
     * Returns the closing line of a run.
     *
     * @return the total line
     */
    public String totalLine() {
        if (counts.isEmpty()) {
            return "Total: 0 files rewritten";
        }
        return "Total: " + filesWritten() + " files rewritten, " + totalReverted() + " technical changes reverted.";
    }

    private static String summaryLine(final String key, final RevertCounts fileCounts) {
        return key + ": " + fileCounts.total() + " reverted (instanceIdentifier=" + fileCounts.instanceIdentifier()
                + ", rootIdentifier=" + fileCounts.rootIdentifier()
                + ", groupIdentifier=" + fileCounts.groupIdentifier()
                + ", endpointGroupId=" + fileCounts.endpointGroupId() + ")";
    }
}
