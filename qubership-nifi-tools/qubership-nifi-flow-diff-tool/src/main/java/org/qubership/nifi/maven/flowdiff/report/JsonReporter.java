package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;

import java.io.IOException;
import java.util.List;

/**
 * Renders a {@link ReportModel} as the flat, machine-readable JSON report for CI gating. Each change is a
 * self-contained record with the full canonical {@code path} and a {@code pathSegments} array. Technical changes are
 * counted in {@code counts} and {@code totals} but not listed, keeping the report small, unless {@code showTechnical}
 * is set, in which case they are listed with {@code category: "technical"}.
 */
public final class JsonReporter {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;
    private final boolean showTechnical;

    /**
     * Creates a JSON reporter.
     *
     * @param mapperValue        the mapper used to build and serialize the report
     * @param showTechnicalValue whether to also list technical changes
     */
    public JsonReporter(final ObjectMapper mapperValue, final boolean showTechnicalValue) {
        this.mapper = mapperValue;
        this.showTechnical = showTechnicalValue;
    }

    /**
     * Renders the model to the JSON report.
     *
     * @param model the diff model
     * @return the JSON report text
     * @throws IOException when serialization fails
     */
    public String render(final ReportModel model) throws IOException {
        JsonNodeFactory factory = mapper.getNodeFactory();
        ObjectNode root = factory.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        ArrayNode flows = root.putArray("flows");
        model.getFlows().forEach(flow -> flows.add(flowNode(flow, factory)));
        root.set("addedFlows", stringArray(model.getAddedFlows(), factory));
        root.set("removedFlows", stringArray(model.getRemovedFlows(), factory));
        root.set("totals", totalsNode(model, factory));
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    private ObjectNode flowNode(final FlowReport flow, final JsonNodeFactory factory) {
        ObjectNode node = factory.objectNode();
        node.put("path", flow.getPath());
        ObjectNode counts = node.putObject("counts");
        counts.put("technical", flow.count(ChangeCategory.TECHNICAL));
        counts.put("environmental", flow.count(ChangeCategory.ENVIRONMENTAL));
        counts.put("significant", flow.count(ChangeCategory.SIGNIFICANT));
        ArrayNode changes = node.putArray("changes");
        for (Difference difference : flow.getChanges()) {
            if (isListed(difference)) {
                changes.add(changeNode(difference, factory));
            }
        }
        return node;
    }

    private ObjectNode changeNode(final Difference difference, final JsonNodeFactory factory) {
        ObjectNode node = factory.objectNode();
        node.put("path", difference.getPath());
        node.set("pathSegments", stringArray(difference.getPathSegments(), factory));
        node.put("category", difference.getCategory().getLabel());
        if (difference.getChange() != null) {
            node.put("change", difference.getChange());
        }
        if (difference.getIdentifier() != null) {
            node.put("identifier", difference.getIdentifier());
        }
        if (difference.getComponentType() != null) {
            node.put("componentType", difference.getComponentType().name());
        }
        if (difference.getName() != null) {
            node.put("name", difference.getName());
        }
        if (difference.getNameBaseline() != null) {
            node.put("nameBaseline", difference.getNameBaseline());
            node.put("nameTarget", difference.getNameTarget());
        }
        if (difference.getChange() == null) {
            node.set("baselineValue", difference.getBaselineValue() == null
                    ? factory.nullNode() : difference.getBaselineValue());
            node.set("targetValue", difference.getTargetValue() == null
                    ? factory.nullNode() : difference.getTargetValue());
        }
        return node;
    }

    private ObjectNode totalsNode(final ReportModel model, final JsonNodeFactory factory) {
        ObjectNode totals = factory.objectNode();
        totals.put("technical", model.total(ChangeCategory.TECHNICAL));
        totals.put("environmental", model.total(ChangeCategory.ENVIRONMENTAL));
        totals.put("significant", model.total(ChangeCategory.SIGNIFICANT));
        totals.put("addedFlows", model.getAddedFlows().size());
        totals.put("removedFlows", model.getRemovedFlows().size());
        return totals;
    }

    private static ArrayNode stringArray(final List<String> values, final JsonNodeFactory factory) {
        ArrayNode array = factory.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private boolean isListed(final Difference difference) {
        ChangeCategory category = difference.getCategory();
        return category == ChangeCategory.SIGNIFICANT || category == ChangeCategory.ENVIRONMENTAL
                || (showTechnical && category == ChangeCategory.TECHNICAL);
    }
}
