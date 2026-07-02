package org.qubership.nifi.maven.flowdiff.report;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.io.SideEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs the discovered entries of two sides and builds the {@link ReportModel}. Directory inputs pair over the union of
 * relative paths; two single-file inputs pair directly regardless of name. A flow present on only one side is a whole
 * added or removed flow; a flow paired against a parseable non-flow JSON at the same path is reported as added or
 * removed with a warning naming both paths.
 */
public final class DiffModelBuilder {

    private final FlowComparator comparator = new FlowComparator();
    private final Log log;

    /**
     * Creates a model builder.
     *
     * @param logValue the Maven log for flow-vs-non-flow warnings
     */
    public DiffModelBuilder(final Log logValue) {
        this.log = logValue;
    }

    /**
     * Builds the diff model from the two sides.
     *
     * @param baseline        the baseline entries keyed by relative path or file name
     * @param target          the target entries keyed by relative path or file name
     * @param bothDirectories whether both inputs are directories (union pairing) rather than single files
     * @return the assembled report model
     */
    public ReportModel build(final Map<String, SideEntry> baseline, final Map<String, SideEntry> target,
            final boolean bothDirectories) {
        List<FlowReport> flows = new ArrayList<>();
        List<String> addedFlows = new ArrayList<>();
        List<String> removedFlows = new ArrayList<>();
        if (bothDirectories) {
            for (String key : union(baseline.keySet(), target.keySet())) {
                pair(baseline.get(key), target.get(key), flows, addedFlows, removedFlows);
            }
        } else {
            pair(single(baseline), single(target), flows, addedFlows, removedFlows);
        }
        flows.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        addedFlows.sort(String::compareTo);
        removedFlows.sort(String::compareTo);
        return new ReportModel(flows, addedFlows, removedFlows);
    }

    private void pair(final SideEntry base, final SideEntry target, final List<FlowReport> flows,
            final List<String> addedFlows, final List<String> removedFlows) {
        boolean baseFlow = base != null && base.isFlow();
        boolean targetFlow = target != null && target.isFlow();
        if (baseFlow && targetFlow) {
            List<Difference> changes = comparator.compare(base.getFlow(), target.getFlow());
            if (!changes.isEmpty()) {
                flows.add(new FlowReport(target.getDisplayPath(), changes));
            }
            return;
        }
        if (baseFlow && !targetFlow && target != null) {
            log.warn("Flow present as baseline but a non-flow JSON on the target side: "
                    + base.getDisplayPath() + " vs " + target.getDisplayPath());
        }
        if (targetFlow && !baseFlow && base != null) {
            log.warn("Flow present as target but a non-flow JSON on the baseline side: "
                    + target.getDisplayPath() + " vs " + base.getDisplayPath());
        }
        if (baseFlow) {
            removedFlows.add(base.getDisplayPath());
        }
        if (targetFlow) {
            addedFlows.add(target.getDisplayPath());
        }
    }

    private static SideEntry single(final Map<String, SideEntry> entries) {
        return entries.values().stream().findFirst().orElse(null);
    }

    private static Set<String> union(final Set<String> a, final Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String::compareTo);
        return new LinkedHashSet<>(sorted);
    }
}
