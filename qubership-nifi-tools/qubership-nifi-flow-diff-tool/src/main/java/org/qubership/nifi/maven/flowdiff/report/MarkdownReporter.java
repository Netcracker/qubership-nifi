package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.EndpointChange;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link ReportModel} as Markdown: a heading and table per process group, with the full component type in a
 * {@code Type} column. Values are wrapped as inline code with {@code |} and backticks escaped so a value cannot break a
 * table cell, and truncated to the value budget. Technical changes appear only in the counts line unless
 * {@code showTechnical} is set, in which case they are listed and marked {@code [tech]}.
 */
public final class MarkdownReporter
        extends AbstractReporter {

    private static final String HEADER = "| Component | Type | Field | Baseline | Target |\n"
            + "| --- | --- | --- | --- | --- |\n";
    private static final String GROUP = "_(group)_";
    private static final String BENDS = "bends";

    /**
     * Creates a Markdown reporter.
     *
     * @param maxValueLengthValue the value truncation budget; {@code 0} disables truncation
     * @param showTechnicalValue  whether to also list technical changes, marked {@code [tech]}
     */
    public MarkdownReporter(final int maxValueLengthValue, final boolean showTechnicalValue) {
        super(maxValueLengthValue, showTechnicalValue);
    }

    /**
     * Renders the model to the Markdown report and returns it as a string.
     *
     * @param model the diff model
     * @return the Markdown report text
     */
    public String render(final ReportModel model) {
        StringWriter out = new StringWriter();
        try {
            render(model, out);
        } catch (IOException e) {
            // A StringWriter never throws, so this cannot happen.
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * Renders the model to the Markdown report, writing each flow to the given writer as it is built so the whole
     * report is never held in memory at once.
     *
     * @param model the diff model
     * @param out   the writer the report is streamed to
     * @throws IOException when writing to the writer fails
     */
    public void render(final ReportModel model, final Writer out) throws IOException {
        for (FlowReport flow : model.getFlows()) {
            StringBuilder sb = new StringBuilder();
            renderFlow(flow, sb);
            out.write(sb.toString());
        }
        StringBuilder tail = new StringBuilder();
        renderWholeFlows(model, tail);
        out.write(tail.toString());
    }

    private void renderFlow(final FlowReport flow, final StringBuilder sb) {
        sb.append("## ").append(flow.getPath()).append("\n\n");
        sb.append("Significant: ").append(flow.count(ChangeCategory.SIGNIFICANT))
                .append(", Environmental: ").append(flow.count(ChangeCategory.ENVIRONMENTAL))
                .append(", Technical: ").append(flow.count(ChangeCategory.TECHNICAL))
                .append(showTechnical
                        ? " (`[env]` marks an environmental change; `[tech]` a technical change; unmarked rows are "
                                + "significant)\n\n"
                        : " (`[env]` marks an environmental change; unmarked rows are significant)\n\n");

        Map<String, List<Difference>> byGroup = new LinkedHashMap<>();
        Map<String, List<GroupRef>> crumbs = new LinkedHashMap<>();
        List<Difference> other = new ArrayList<>();
        for (Difference difference : flow.getChanges()) {
            if (!isListable(difference)) {
                continue;
            }
            if (difference.isOtherAttributes()) {
                other.add(difference);
                continue;
            }
            String key = groupKey(difference.getBreadcrumb());
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(difference);
            crumbs.putIfAbsent(key, difference.getBreadcrumb());
        }
        crumbs.entrySet().stream()
                .sorted(Comparator.comparing(entry -> crumbDisplay(entry.getValue())))
                .forEach(entry -> renderGroup(crumbDisplay(entry.getValue()), byGroup.get(entry.getKey()), sb));
        if (!other.isEmpty()) {
            renderGroup("other attributes", other, sb);
        }
    }

    private void renderGroup(final String heading, final List<Difference> diffs, final StringBuilder sb) {
        sb.append("### ").append(heading).append("\n\n").append(HEADER);
        Map<String, Set<String>> collapsed = collapsedRolesByComponent(diffs);
        Map<String, String> positionTriggers = positionTriggersByComponent(diffs);
        diffs.stream()
                .sorted(Comparator.comparing((Difference d) -> d.getShortLabel() == null ? "" : d.getShortLabel())
                        .thenComparing(d -> d.getFieldPath() == null ? "" : d.getFieldPath()))
                .forEach(difference -> {
                    if (difference.getEndpointChange() != null) {
                        sb.append(endpointRow(difference));
                    } else if (difference.getPositionChange() != null) {
                        if (difference.getFieldPath().equals(positionTriggers.get(difference.getIdentifier()))) {
                            sb.append(positionRow(difference));
                        }
                    } else if (!isCollapsedEndpointField(difference, collapsed)) {
                        sb.append(row(difference));
                    }
                });
        sb.append('\n');
    }

    private String positionRow(final Difference difference) {
        String field = categoryMarker(difference.getCategory()) + code("position");
        return "| " + componentCell(difference) + " | " + typeCell(difference) + " | " + field
                + " | " + codeValue(CoordinateFormat.pair(difference.getPositionChange().baseline()))
                + " | " + codeValue(CoordinateFormat.pair(difference.getPositionChange().target())) + " |\n";
    }

    private static Map<String, String> positionTriggersByComponent(final List<Difference> diffs) {
        Map<String, String> triggers = new HashMap<>();
        for (Difference difference : diffs) {
            if (difference.getPositionChange() == null || difference.getFieldPath() == null) {
                continue;
            }
            triggers.merge(difference.getIdentifier(), difference.getFieldPath(),
                    (existing, candidate) -> existing.compareTo(candidate) <= 0 ? existing : candidate);
        }
        return triggers;
    }

    private String row(final Difference difference) {
        return "| " + componentCell(difference) + " | " + typeCell(difference) + " | " + fieldCell(difference)
                + " | " + baselineCell(difference) + " | " + targetCell(difference) + " |\n";
    }

    private String endpointRow(final Difference difference) {
        EndpointChange change = difference.getEndpointChange();
        return "| " + componentCell(difference) + " | " + typeCell(difference) + " | " + code(change.role())
                + " | " + codeValue(endpoint(change.baseline(), true))
                + " | " + codeValue(endpoint(change.target(), true))
                + " |\n";
    }

    private static Map<String, Set<String>> collapsedRolesByComponent(final List<Difference> diffs) {
        Map<String, Set<String>> collapsed = new HashMap<>();
        for (Difference difference : diffs) {
            if (difference.getEndpointChange() != null) {
                collapsed.computeIfAbsent(difference.getIdentifier(), k -> new HashSet<>())
                        .add(difference.getEndpointChange().role());
            }
        }
        return collapsed;
    }

    private static boolean isCollapsedEndpointField(final Difference difference,
            final Map<String, Set<String>> collapsed) {
        Set<String> roles = collapsed.get(difference.getIdentifier());
        if (roles == null || difference.getFieldPath() == null) {
            return false;
        }
        for (String role : roles) {
            if (difference.getFieldPath().startsWith(role + "/")) {
                return true;
            }
        }
        return false;
    }

    private String componentCell(final Difference difference) {
        if (difference.isOtherAttributes() && difference.getShortLabel() == null) {
            return code(difference.getFieldPath());
        }
        return difference.getShortLabel() == null ? GROUP : code(difference.getShortLabel());
    }

    private String typeCell(final Difference difference) {
        if (difference.getComponentType() != null) {
            return difference.getComponentType().name();
        }
        if (difference.isOtherAttributes()) {
            return difference.getShortLabel() == null ? "_(environmental)_" : "_(parameter)_";
        }
        return "PROCESS_GROUP";
    }

    private String fieldCell(final Difference difference) {
        if ("added".equals(difference.getChange())) {
            return "_(added)_";
        }
        if ("removed".equals(difference.getChange())) {
            return "_(removed)_";
        }
        String marker = categoryMarker(difference.getCategory());
        if (difference.isOtherAttributes() && difference.getShortLabel() == null) {
            return marker.trim();
        }
        return marker + code(difference.getFieldPath());
    }

    private String baselineCell(final Difference difference) {
        if ("added".equals(difference.getChange())) {
            return "_(absent)_";
        }
        if ("removed".equals(difference.getChange())) {
            return "_(present)_";
        }
        return valueCell(difference, difference.getBaselineValue());
    }

    private String targetCell(final Difference difference) {
        if ("added".equals(difference.getChange())) {
            return "_(present)_";
        }
        if ("removed".equals(difference.getChange())) {
            return "_(absent)_";
        }
        return valueCell(difference, difference.getTargetValue());
    }

    private String valueCell(final Difference difference, final JsonNode value) {
        if (BENDS.equals(difference.getFieldPath())) {
            return codeValue(CoordinateFormat.bends(value));
        }
        String formatted = ValueFormatter.format(value, maxValueLength);
        return formatted.isEmpty() ? "" : codeValue(formatted);
    }

    private void renderWholeFlows(final ReportModel model, final StringBuilder sb) {
        if (!model.getAddedFlows().isEmpty()) {
            sb.append("Added flows: ").append(codeList(model.getAddedFlows())).append("\n\n");
        }
        if (!model.getRemovedFlows().isEmpty()) {
            sb.append("Removed flows: ").append(codeList(model.getRemovedFlows())).append("\n\n");
        }
    }

    private static String codeList(final List<String> values) {
        List<String> wrapped = new ArrayList<>();
        values.forEach(value -> wrapped.add(code(value)));
        return String.join(", ", wrapped);
    }

    private static String code(final String value) {
        return "`" + escapeCode(value == null ? "" : value) + "`";
    }

    private static String codeValue(final String value) {
        return "`" + escapeCode(value) + "`";
    }

    private static String escapeCode(final String value) {
        return value.replace("`", "\\`").replace("|", "\\|");
    }
}
