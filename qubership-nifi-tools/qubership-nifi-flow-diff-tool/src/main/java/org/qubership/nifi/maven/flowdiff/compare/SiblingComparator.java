package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.EXTERNAL_CONTROLLER_SERVICES;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.FLOW_ENCODING_VERSION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.NAME;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PARAMETERS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PARAMETER_CONTEXTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PARAMETER_PROVIDERS;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.asText;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.textOrEmpty;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.getUniqueSortedFieldNames;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.nodeEquals;
import static org.qubership.nifi.maven.flowdiff.compare.Difference.ADDED;
import static org.qubership.nifi.maven.flowdiff.compare.Difference.REMOVED;

/**
 * Compares the non-{@code flowContents} sibling sections of two exports: {@code flowEncodingVersion} (environmental),
 * {@code parameterContexts} and their {@code parameters} (matched by name), {@code parameterProviders} and
 * {@code externalControllerServices} (matched by identifier). {@code snapshotMetadata} is dropped. Every difference is
 * flagged as an {@code other attributes} record so the human renderers bundle it under one header.
 */
public final class SiblingComparator {

    private static final String SEPARATOR = " / ";

    /**
     * Compares the sibling sections of a baseline and a target export root.
     *
     * @param baselineRoot the baseline export root object
     * @param targetRoot   the target export root object
     * @return the sibling-section differences
     */
    public List<Difference> compare(final JsonNode baselineRoot, final JsonNode targetRoot) {
        List<Difference> diffs = new ArrayList<>();
        compareFlowEncodingVersion(baselineRoot, targetRoot, diffs);
        compareParameterContexts(map(baselineRoot, PARAMETER_CONTEXTS), map(targetRoot, PARAMETER_CONTEXTS), diffs);
        compareIdentifiedEntries(map(baselineRoot, PARAMETER_PROVIDERS), map(targetRoot, PARAMETER_PROVIDERS),
                PARAMETER_PROVIDERS, ComponentType.PARAMETER_PROVIDER, diffs);
        compareIdentifiedEntries(map(baselineRoot, EXTERNAL_CONTROLLER_SERVICES),
                map(targetRoot, EXTERNAL_CONTROLLER_SERVICES), EXTERNAL_CONTROLLER_SERVICES,
                ComponentType.CONTROLLER_SERVICE, diffs);
        return diffs;
    }

    private void compareFlowEncodingVersion(final JsonNode baselineRoot, final JsonNode targetRoot,
            final List<Difference> diffs) {
        JsonNode baseline = baselineRoot.get(FLOW_ENCODING_VERSION);
        JsonNode target = targetRoot.get(FLOW_ENCODING_VERSION);
        if (!nodeEquals(baseline, target)) {
            diffs.add(Difference.builder().otherAttributes(true).category(ChangeCategory.ENVIRONMENTAL)
                    .pathSegments(List.of(FLOW_ENCODING_VERSION)).path(FLOW_ENCODING_VERSION)
                    .fieldPath(FLOW_ENCODING_VERSION).values(baseline, target).build());
        }
    }

    private void compareParameterContexts(final JsonNode baseline, final JsonNode target,
            final List<Difference> diffs) {
        for (String name : getUniqueSortedFieldNames(baseline, target)) {
            JsonNode base = baseline.get(name);
            JsonNode tgt = target.get(name);
            if (base != null && tgt != null) {
                compareContextFields(name, base, tgt, diffs);
                compareParameters(name, params(base), params(tgt), diffs);
            } else {
                diffs.add(wholeEntry(base == null ? ADDED : REMOVED,
                        List.of(PARAMETER_CONTEXTS, name), PARAMETER_CONTEXTS + SEPARATOR + name, null, null, null));
            }
        }
    }

    private void compareContextFields(final String name, final JsonNode base, final JsonNode tgt,
            final List<Difference> diffs) {
        for (LeafDiff leaf : new NodeDiffer(Set.of(PARAMETERS)).diff(base, tgt)) {
            List<String> segments = concat(List.of(PARAMETER_CONTEXTS, name), leaf.relPath());
            diffs.add(fieldDiff(ChangeCategory.SIGNIFICANT, segments, PARAMETER_CONTEXTS + SEPARATOR + name,
                    null, null, null, leaf));
        }
    }

    private void compareParameters(final String context, final JsonNode baseline, final JsonNode target,
            final List<Difference> diffs) {
        for (String name : getUniqueSortedFieldNames(baseline, target)) {
            JsonNode base = baseline.get(name);
            JsonNode tgt = target.get(name);
            String label = PARAMETER_CONTEXTS + SEPARATOR + context + SEPARATOR + name;
            if (base != null && tgt != null) {
                for (LeafDiff leaf : new NodeDiffer(Set.of()).diff(base, tgt)) {
                    List<String> segments = concat(List.of(PARAMETER_CONTEXTS, context, PARAMETERS, name),
                            leaf.relPath());
                    diffs.add(fieldDiff(ChangeCategory.SIGNIFICANT, segments, label, null, null, null, leaf));
                }
            } else {
                diffs.add(wholeEntry(base == null ? ADDED : REMOVED,
                        List.of(PARAMETER_CONTEXTS, context, PARAMETERS, name), label, null, null, null));
            }
        }
    }

    private void compareIdentifiedEntries(final JsonNode baseline, final JsonNode target, final String section,
            final ComponentType type, final List<Difference> diffs) {
        for (String id : getUniqueSortedFieldNames(baseline, target)) {
            JsonNode base = baseline.get(id);
            JsonNode tgt = target.get(id);
            String name = textOrEmpty(tgt != null ? tgt : base, NAME);
            String entrySegment = name + '(' + id + ')';
            String label = section + SEPARATOR + name;
            if (base != null && tgt != null) {
                for (LeafDiff leaf : new NodeDiffer(Set.of()).diff(base, tgt)) {
                    ChangeCategory category = ChangeCategorizer.isBundleVersion(leaf.relPath(), tgt)
                            ? ChangeCategory.ENVIRONMENTAL : ChangeCategory.SIGNIFICANT;
                    List<String> segments = concat(List.of(section, entrySegment), leaf.relPath());
                    diffs.add(fieldDiff(category, segments, label, type, id, name, leaf));
                }
            } else {
                diffs.add(wholeEntry(base == null ? ADDED : REMOVED,
                        List.of(section, entrySegment), label, type, id, name));
            }
        }
    }

    private Difference fieldDiff(final ChangeCategory category, final List<String> segments, final String label,
            final ComponentType type, final String identifier, final String name, final LeafDiff leaf) {
        Difference.Builder builder = Difference.builder().otherAttributes(true).category(category)
                .pathSegments(segments).path(CanonicalPath.display(segments)).shortLabel(label)
                .componentType(type).identifier(identifier).name(name)
                .fieldPath(String.join("/", leaf.relPath())).values(leaf.baseline(), leaf.target());
        if (leaf.relPath().size() == 1 && NAME.equals(leaf.relPath().get(0))) {
            builder.renamed(asText(leaf.baseline()), asText(leaf.target()));
        }
        return builder.build();
    }

    private Difference wholeEntry(final String change, final List<String> segments, final String label,
            final ComponentType type, final String identifier, final String name) {
        return Difference.builder().otherAttributes(true).category(ChangeCategory.SIGNIFICANT).change(change)
                .pathSegments(segments).path(CanonicalPath.display(segments)).shortLabel(label)
                .componentType(type).identifier(identifier).name(name).build();
    }

    private static JsonNode map(final JsonNode root, final String field) {
        JsonNode node = root.get(field);
        return node != null && node.isObject() ? node : MissingNode.getInstance();
    }

    private static JsonNode params(final JsonNode context) {
        JsonNode array = context.get(PARAMETERS);
        com.fasterxml.jackson.databind.node.ObjectNode byName =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (array != null && array.isArray()) {
            for (JsonNode param : array) {
                byName.set(textOrEmpty(param, NAME), param);
            }
        }
        return byName;
    }

    private static List<String> concat(final List<String> prefix, final List<String> suffix) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(suffix);
        return all;
    }
}
