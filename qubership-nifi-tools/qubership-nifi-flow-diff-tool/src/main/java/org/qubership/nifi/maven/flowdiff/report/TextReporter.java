package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.EndpointChange;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.qubership.nifi.maven.flowdiff.compare.Difference.ADDED;

/**
 * Renders a {@link ReportModel} as the human-readable text report: a legend of the type codes it uses, then per flow a
 * counts header followed by the changes as a grouped tree. Significant changes are unmarked and environmental changes
 * are marked {@code [env]}. Technical changes appear only in the counts header unless {@code showTechnical} is set, in
 * which case they are listed and marked {@code [tech]}.
 */
public final class TextReporter extends AbstractReporter {

    private static final Map<ComponentType, String> CODE_NAMES = codeNames();
    private static final int INDENT_COMPONENT = 4;
    private static final int INDENT_COMPONENT_FIELD = 6;
    private static final String BENDS = "bends";
    private static final String EMPTY_MARKER = "(empty)";


    /**
     * Creates a text reporter.
     *
     * @param maxValueLengthValue the value truncation budget; {@code 0} disables truncation
     * @param showTechnicalValue  whether to also list technical changes, marked {@code [tech]}
     */
    public TextReporter(final int maxValueLengthValue, final boolean showTechnicalValue) {
        super(maxValueLengthValue, showTechnicalValue);
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
     * Renders the model to the text report and returns it as a string.
     *
     * @param model the diff model
     * @return the text report
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
     * Renders the model to the text report, writing each flow to the given writer as it is built so the whole
     * report is never held in memory at once.
     *
     * @param model the diff model
     * @param out   the writer the report is streamed to
     * @throws IOException when writing to the writer fails
     */
    public void render(final ReportModel model, final Writer out) throws IOException {
        String legend = legend(model);
        if (!legend.isEmpty()) {
            out.write(legend);
            out.write("\n");
        }
        for (FlowReport flow : model.getFlows()) {
            StringBuilder sb = new StringBuilder();
            renderFlow(flow, sb);
            out.write(sb.toString());
        }
        StringBuilder tail = new StringBuilder();
        renderWholeFlows(model, tail);
        renderTotals(model, tail);
        out.write(tail.toString());
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
        List<Difference> ownFields = diffs.stream().filter(difference -> difference.getShortLabel() == null)
                .sorted(Comparator.comparing(Difference::getFieldPath))
                .toList();
        String ownPositionTrigger = positionTrigger(ownFields);
        ownFields.forEach(difference -> {
            if (!renderPositionCollapsed(difference, INDENT_COMPONENT, ownPositionTrigger, sb)) {
                renderField(difference, INDENT_COMPONENT, sb);
            }
        });

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
            sb.append("    ").append(ADDED.equals(head.getChange()) ? "+ " : "- ")
                    .append(prefix).append(head.getShortLabel())
                    .append(ADDED.equals(head.getChange()) ? " (added)" : " (removed)").append('\n');
            return;
        }
        sb.append("    ").append(prefix).append(head.getShortLabel()).append('\n');
        Set<String> collapsedRoles = collapsedRoles(componentDiffs);
        String positionTrigger = positionTrigger(componentDiffs);
        componentDiffs.stream().sorted(Comparator.comparing(Difference::getFieldPath))
                .forEach(difference -> renderComponentField(difference, collapsedRoles, positionTrigger, sb));
    }

    private void renderComponentField(final Difference difference, final Set<String> collapsedRoles,
            final String positionTrigger, final StringBuilder sb) {
        EndpointChange endpointChange = difference.getEndpointChange();
        if (endpointChange != null) {
            renderEndpointChange(endpointChange, sb);
            return;
        }
        if (isCollapsedEndpointField(difference, collapsedRoles)) {
            return;
        }
        if (renderPositionCollapsed(difference, INDENT_COMPONENT_FIELD, positionTrigger, sb)) {
            return;
        }
        renderField(difference, INDENT_COMPONENT_FIELD, sb);
    }

    private boolean renderPositionCollapsed(final Difference difference, final int indent,
            final String positionTrigger, final StringBuilder sb) {
        if (difference.getPositionChange() == null) {
            return false;
        }
        if (difference.getFieldPath().equals(positionTrigger)) {
            sb.append(" ".repeat(indent));
            appendMarker(difference, sb);
            sb.append("position: ")
                    .append(CoordinateFormat.pair(difference.getPositionChange().baseline()))
                    .append(" -> ")
                    .append(CoordinateFormat.pair(difference.getPositionChange().target()))
                    .append('\n');
        }
        return true;
    }

    private static String positionTrigger(final List<Difference> diffs) {
        return diffs.stream()
                .filter(difference -> difference.getPositionChange() != null && difference.getFieldPath() != null)
                .map(Difference::getFieldPath)
                .min(String::compareTo)
                .orElse(null);
    }

    private void renderEndpointChange(final EndpointChange change, final StringBuilder sb) {
        sb.append(" ".repeat(INDENT_COMPONENT_FIELD))
                .append(change.role()).append(": ")
                .append(endpoint(change.baseline())).append(" -> ").append(endpoint(change.target()))
                .append('\n');
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
        appendMarker(difference, sb);
        sb.append(difference.getFieldPath()).append(": ")
                .append(fieldValue(difference, difference.getBaselineValue()))
                .append(" -> ")
                .append(fieldValue(difference, difference.getTargetValue()))
                .append('\n');
    }

    private String fieldValue(final Difference difference, final JsonNode value) {
        if (BENDS.equals(difference.getFieldPath())) {
            return CoordinateFormat.bends(value);
        }
        String formatted = ValueFormatter.format(value, maxValueLength);
        return formatted.isEmpty() ? EMPTY_MARKER : formatted;
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
}
