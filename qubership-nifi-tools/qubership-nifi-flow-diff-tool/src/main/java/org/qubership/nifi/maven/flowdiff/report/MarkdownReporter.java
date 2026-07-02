package org.qubership.nifi.maven.flowdiff.report;

import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.ShortLabel;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ReportModel} as Markdown: a heading and table per process group, with the full component type in a
 * {@code Type} column. Values are wrapped as inline code with {@code |} and backticks escaped so a value cannot break a
 * table cell, and truncated to the value budget. Technical changes appear only in the counts line.
 */
public final class MarkdownReporter {

    private static final String HEADER = "| Component | Type | Field | Baseline | Target |\n"
            + "| --- | --- | --- | --- | --- |\n";
    private static final String GROUP = "_(group)_";

    private final int maxValueLength;

    /**
     * Creates a Markdown reporter.
     *
     * @param maxValueLengthValue the value truncation budget; {@code 0} disables truncation
     */
    public MarkdownReporter(final int maxValueLengthValue) {
        this.maxValueLength = maxValueLengthValue;
    }

    /**
     * Renders the model to the Markdown report.
     *
     * @param model the diff model
     * @return the Markdown report text
     */
    public String render(final ReportModel model) {
        StringBuilder sb = new StringBuilder();
        model.getFlows().forEach(flow -> renderFlow(flow, sb));
        renderWholeFlows(model, sb);
        return sb.toString();
    }

    private void renderFlow(final FlowReport flow, final StringBuilder sb) {
        sb.append("## ").append(flow.getPath()).append("\n\n");
        sb.append("Significant: ").append(flow.count(ChangeCategory.SIGNIFICANT))
                .append(", Environmental: ").append(flow.count(ChangeCategory.ENVIRONMENTAL))
                .append(", Technical: ").append(flow.count(ChangeCategory.TECHNICAL))
                .append(" (`[env]` marks an environmental change; unmarked rows are significant)\n\n");

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
        diffs.stream()
                .sorted(Comparator.comparing((Difference d) -> d.getShortLabel() == null ? "" : d.getShortLabel())
                        .thenComparing(d -> d.getFieldPath() == null ? "" : d.getFieldPath()))
                .forEach(difference -> sb.append(row(difference)));
        sb.append('\n');
    }

    private String row(final Difference difference) {
        return "| " + componentCell(difference) + " | " + typeCell(difference) + " | " + fieldCell(difference)
                + " | " + baselineCell(difference) + " | " + targetCell(difference) + " |\n";
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
        String marker = difference.getCategory() == ChangeCategory.ENVIRONMENTAL ? "[env] " : "";
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
        return codeValue(ValueFormatter.format(difference.getBaselineValue(), maxValueLength));
    }

    private String targetCell(final Difference difference) {
        if ("added".equals(difference.getChange())) {
            return "_(present)_";
        }
        if ("removed".equals(difference.getChange())) {
            return "_(absent)_";
        }
        return codeValue(ValueFormatter.format(difference.getTargetValue(), maxValueLength));
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

    private static boolean isListable(final Difference difference) {
        ChangeCategory category = difference.getCategory();
        return category == ChangeCategory.SIGNIFICANT || category == ChangeCategory.ENVIRONMENTAL;
    }

    private static String groupKey(final List<GroupRef> breadcrumb) {
        List<String> ids = new ArrayList<>();
        breadcrumb.forEach(group -> ids.add(group.identifier()));
        return String.join("/", ids);
    }

    private static String crumbDisplay(final List<GroupRef> breadcrumb) {
        List<String> labels = new ArrayList<>();
        breadcrumb.forEach(group -> labels.add(ShortLabel.group(group)));
        return String.join(" / ", labels);
    }
}
