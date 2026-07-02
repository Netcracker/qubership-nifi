package org.qubership.nifi.maven.flowdiff.report;

import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.EndpointChange;
import org.qubership.nifi.maven.flowdiff.compare.ShortLabel;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renders a {@link ReportModel} as the human-readable text report: a legend of the type codes it uses, then per flow a
 * counts header followed by the changes as a grouped tree. Significant changes are unmarked and environmental changes
 * are marked {@code [env]}. Technical changes appear only in the counts header unless {@code showTechnical} is set, in
 * which case they are listed and marked {@code [tech]}.
 */
public final class TextReporter {

    private static final Map<ComponentType, String> CODE_NAMES = codeNames();
    private static final int INDENT_COMPONENT = 4;
    private static final int INDENT_COMPONENT_FIELD = 6;

    private final int maxValueLength;
    private final boolean showTechnical;

    /**
     * Creates a text reporter.
     *
     * @param maxValueLengthValue the value truncation budget; {@code 0} disables truncation
     * @param showTechnicalValue  whether to also list technical changes, marked {@code [tech]}
     */
    public TextReporter(final int maxValueLengthValue, final boolean showTechnicalValue) {
        this.maxValueLength = maxValueLengthValue;
        this.showTechnical = showTechnicalValue;
    }

    private static Map<ComponentType, String> codeNames() {
        Map<ComponentType, String> map = new EnumMap<>(ComponentType.class);
        map.put(ComponentType.PROCESSOR, "processor");
        map.put(ComponentType.CONTROLLER_SERVICE, "controller service");
        map.put(ComponentType.INPUT_PORT, "input port");
        map.put(ComponentType.OUTPUT_PORT, "output port");
        map.put(ComponentType.FUNNEL, "funnel");
        map.put(ComponentType.LABEL, "label");
        map.put(ComponentType.REMOTE_PROCESS_GROUP, "remote process group");
        map.put(ComponentType.REMOTE_INPUT_PORT, "remote input port");
        map.put(ComponentType.REMOTE_OUTPUT_PORT, "remote output port");
        map.put(ComponentType.CONNECTION, "connection");
        return map;
    }

    /**
     * Renders the model to the text report.
     *
     * @param model the diff model
     * @return the text report
     */
    public String render(final ReportModel model) {
        StringBuilder sb = new StringBuilder();
        String legend = legend(model);
        if (!legend.isEmpty()) {
            sb.append(legend).append('\n');
        }
        for (FlowReport flow : model.getFlows()) {
            renderFlow(flow, sb);
        }
        renderWholeFlows(model, sb);
        renderTotals(model, sb);
        return sb.toString();
    }

    private String legend(final ReportModel model) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ComponentType, String> entry : CODE_NAMES.entrySet()) {
            if (usesCode(model, entry.getKey())) {
                parts.add(entry.getKey().getCode() + " = " + entry.getValue());
            }
        }
        return parts.isEmpty() ? "" : "Types: " + String.join(", ", parts);
    }

    private boolean usesCode(final ReportModel model, final ComponentType type) {
        return model.getFlows().stream().flatMap(flow -> flow.getChanges().stream())
                .anyMatch(difference -> isListable(difference) && !difference.isOtherAttributes()
                        && difference.getShortLabel() != null && difference.getComponentType() == type);
    }

    private void renderFlow(final FlowReport flow, final StringBuilder sb) {
        sb.append(flow.getPath())
                .append("  (significant: ").append(flow.count(ChangeCategory.SIGNIFICANT))
                .append(", environmental: ").append(flow.count(ChangeCategory.ENVIRONMENTAL))
                .append(", technical: ").append(flow.count(ChangeCategory.TECHNICAL))
                .append(")\n");

        Map<String, List<Difference>> byGroup = new LinkedHashMap<>();
        Map<String, List<GroupRef>> crumbs = new LinkedHashMap<>();
        List<Difference> otherAttributes = new ArrayList<>();
        for (Difference difference : flow.getChanges()) {
            if (!isListable(difference)) {
                continue;
            }
            if (difference.isOtherAttributes()) {
                otherAttributes.add(difference);
                continue;
            }
            String key = groupKey(difference.getBreadcrumb());
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(difference);
            crumbs.putIfAbsent(key, difference.getBreadcrumb());
        }
        crumbs.entrySet().stream()
                .sorted(Comparator.comparing(entry -> crumbDisplay(entry.getValue())))
                .forEach(entry -> renderGroup(crumbDisplay(entry.getValue()), byGroup.get(entry.getKey()), sb));
        if (!otherAttributes.isEmpty()) {
            renderGroup("other attributes", otherAttributes, sb);
        }
    }

    private void renderGroup(final String crumb, final List<Difference> diffs, final StringBuilder sb) {
        sb.append("  ").append(crumb).append('\n');
        diffs.stream().filter(difference -> difference.getShortLabel() == null)
                .sorted(Comparator.comparing(Difference::getFieldPath))
                .forEach(difference -> renderField(difference, INDENT_COMPONENT, sb));

        Map<String, List<Difference>> components = new TreeMap<>();
        for (Difference difference : diffs) {
            if (difference.getShortLabel() != null) {
                components.computeIfAbsent(componentKey(difference), k -> new ArrayList<>()).add(difference);
            }
        }
        components.values().forEach(componentDiffs -> renderComponent(componentDiffs, sb));
    }

    private void renderComponent(final List<Difference> componentDiffs, final StringBuilder sb) {
        Difference head = componentDiffs.get(0);
        String prefix = head.isOtherAttributes() ? "" : codePrefix(head.getComponentType());
        if (head.getChange() != null) {
            sb.append("    ").append("added".equals(head.getChange()) ? "+ " : "- ")
                    .append(prefix).append(head.getShortLabel())
                    .append("added".equals(head.getChange()) ? " (added)" : " (removed)").append('\n');
            return;
        }
        sb.append("    ").append(prefix).append(head.getShortLabel()).append('\n');
        Set<String> collapsedRoles = collapsedRoles(componentDiffs);
        componentDiffs.stream().sorted(Comparator.comparing(Difference::getFieldPath))
                .forEach(difference -> renderComponentField(difference, collapsedRoles, sb));
    }

    private void renderComponentField(final Difference difference, final Set<String> collapsedRoles,
            final StringBuilder sb) {
        EndpointChange endpointChange = difference.getEndpointChange();
        if (endpointChange != null) {
            renderEndpointChange(endpointChange, sb);
            return;
        }
        if (isCollapsedEndpointField(difference, collapsedRoles)) {
            return;
        }
        renderField(difference, INDENT_COMPONENT_FIELD, sb);
    }

    private void renderEndpointChange(final EndpointChange change, final StringBuilder sb) {
        sb.append(" ".repeat(INDENT_COMPONENT_FIELD))
                .append(change.role()).append(": ")
                .append(endpoint(change.baseline())).append(" -> ").append(endpoint(change.target()))
                .append('\n');
    }

    private static String endpoint(final EndpointChange.EndpointRef ref) {
        return "[" + ref.typeCode() + "] " + ref.label() + " (" + ref.identifier() + ")";
    }

    private static Set<String> collapsedRoles(final List<Difference> componentDiffs) {
        Set<String> roles = new HashSet<>();
        for (Difference difference : componentDiffs) {
            if (difference.getEndpointChange() != null) {
                roles.add(difference.getEndpointChange().role());
            }
        }
        return roles;
    }

    private static boolean isCollapsedEndpointField(final Difference difference, final Set<String> collapsedRoles) {
        String fieldPath = difference.getFieldPath();
        if (fieldPath == null) {
            return false;
        }
        for (String role : collapsedRoles) {
            if (fieldPath.startsWith(role + "/")) {
                return true;
            }
        }
        return false;
    }

    private void renderField(final Difference difference, final int indent, final StringBuilder sb) {
        sb.append(" ".repeat(indent));
        if (difference.getCategory() == ChangeCategory.ENVIRONMENTAL) {
            sb.append("[env] ");
        } else if (difference.getCategory() == ChangeCategory.TECHNICAL) {
            sb.append("[tech] ");
        }
        sb.append(difference.getFieldPath()).append(": ")
                .append(ValueFormatter.format(difference.getBaselineValue(), maxValueLength))
                .append(" -> ")
                .append(ValueFormatter.format(difference.getTargetValue(), maxValueLength))
                .append('\n');
    }

    private void renderWholeFlows(final ReportModel model, final StringBuilder sb) {
        if (model.getAddedFlows().isEmpty() && model.getRemovedFlows().isEmpty()) {
            return;
        }
        sb.append('\n');
        model.getAddedFlows().forEach(path -> sb.append("added flow: ").append(path).append('\n'));
        model.getRemovedFlows().forEach(path -> sb.append("removed flow: ").append(path).append('\n'));
    }

    private void renderTotals(final ReportModel model, final StringBuilder sb) {
        sb.append('\n')
                .append("Totals: significant ").append(model.total(ChangeCategory.SIGNIFICANT))
                .append(", environmental ").append(model.total(ChangeCategory.ENVIRONMENTAL))
                .append(", technical ").append(model.total(ChangeCategory.TECHNICAL))
                .append(", added flows ").append(model.getAddedFlows().size())
                .append(", removed flows ").append(model.getRemovedFlows().size())
                .append('\n');
    }

    private static String codePrefix(final ComponentType type) {
        if (type == null || type.getCode().isEmpty()) {
            return "";
        }
        return "[" + type.getCode() + "] ";
    }

    private boolean isListable(final Difference difference) {
        ChangeCategory category = difference.getCategory();
        return category == ChangeCategory.SIGNIFICANT || category == ChangeCategory.ENVIRONMENTAL
                || (showTechnical && category == ChangeCategory.TECHNICAL);
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

    private static String componentKey(final Difference difference) {
        return difference.getShortLabel() + ' ' + difference.getIdentifier();
    }
}
