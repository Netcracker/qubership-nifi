package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentIndex;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.CONNECTIONS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.CONTENTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.CONTROLLER_SERVICES;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.FUNNELS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.INPUT_PORTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.LABELS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.NAME;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.OUTPUT_PORTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.POSITION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PROCESSORS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PROCESS_GROUPS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.REMOTE_PROCESS_GROUPS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.TYPE;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ENDPOINT_ROLES;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.asText;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.text;

/**
 * Compares two flow exports and produces the ordered list of {@link Difference} records for the {@code flowContents}
 * tree. Components are matched by identity, so array reordering never registers; the root process group is matched by
 * location. An added or removed component is a single significant change, and a whole added or removed subtree is
 * reported only at its top group, not repeated for every descendant.
 */
public final class FlowComparator {

    private static final Set<String> GROUP_COLLECTIONS = Set.of(
            PROCESSORS, CONTROLLER_SERVICES, INPUT_PORTS, OUTPUT_PORTS, FUNNELS, LABELS,
            CONNECTIONS, REMOTE_PROCESS_GROUPS, PROCESS_GROUPS);
    private static final Set<String> REMOTE_GROUP_COLLECTIONS = Set.of(INPUT_PORTS, OUTPUT_PORTS, CONTENTS);

    /**
     * Compares a baseline export against a target export.
     *
     * @param baseline the baseline export
     * @param target   the target export
     * @return the differences for this flow, in a deterministic order
     */
    public List<Difference> compare(final FlowExport baseline, final FlowExport target) {
        ComponentIndex baselineIndex = ComponentIndex.build(baseline);
        ComponentIndex targetIndex = ComponentIndex.build(target);
        String baselineRootId = baselineIndex.getRoot().getIdentifier();
        String targetRootId = targetIndex.getRoot().getIdentifier();
        List<Difference> out = new ArrayList<>();

        compareMatched(baselineIndex.getRoot(), targetIndex.getRoot(), baselineRootId, targetRootId, out);

        Map<String, IndexedComponent> baseById = baselineIndex.getByIdentifier();
        Map<String, IndexedComponent> targetById = targetIndex.getByIdentifier();
        for (String id : union(baseById.keySet(), targetById.keySet())) {
            IndexedComponent base = baseById.get(id);
            IndexedComponent tgt = targetById.get(id);
            if (base != null && tgt != null) {
                compareMatched(base, tgt, baselineRootId, targetRootId, out);
            } else if (tgt != null) {
                addWholeComponent(tgt, "added", targetById.keySet(), out);
            } else {
                addWholeComponent(base, "removed", baseById.keySet(), out);
            }
        }
        out.addAll(new SiblingComparator().compare(baseline.getRoot(), target.getRoot()));
        return out;
    }

    private void compareMatched(final IndexedComponent base, final IndexedComponent target,
            final String baselineRootId, final String targetRootId, final List<Difference> out) {
        NodeDiffer differ = new NodeDiffer(excludedKeys(target.getType()));
        for (LeafDiff leaf : differ.diff(base.getNode(), target.getNode())) {
            out.add(fieldDifference(base.getNode(), target, leaf, baselineRootId, targetRootId));
        }
    }

    private Difference fieldDifference(final JsonNode baselineNode, final IndexedComponent labelComponent,
            final LeafDiff leaf, final String baselineRootId, final String targetRootId) {
        ChangeCategory category = ChangeCategorizer.categorize(labelComponent, leaf.relPath(), baselineNode,
                labelComponent.getNode(), baselineRootId, targetRootId);
        List<String> segments = CanonicalPath.withField(
                CanonicalPath.componentSegments(labelComponent), leaf.relPath());
        boolean root = labelComponent.isRoot();
        boolean groupOwn = labelComponent.getType() == ComponentType.PROCESS_GROUP;
        List<GroupRef> breadcrumb = groupOwn ? ownGroupBreadcrumb(labelComponent) : labelComponent.getAncestors();
        Difference.Builder builder = Difference.builder()
                .category(category)
                .pathSegments(segments)
                .path(CanonicalPath.display(segments))
                .breadcrumb(breadcrumb)
                .fieldPath(String.join("/", leaf.relPath()))
                .values(leaf.baseline(), leaf.target());
        if (!groupOwn) {
            builder.shortLabel(ShortLabel.component(labelComponent));
        }
        if (!root) {
            builder.componentType(labelComponent.getType())
                    .identifier(labelComponent.getIdentifier())
                    .name(labelComponent.getName());
        }
        if (leaf.relPath().size() == 1 && NAME.equals(leaf.relPath().get(0))) {
            builder.renamed(asText(leaf.baseline()), asText(leaf.target()));
        }
        if (labelComponent.getType() == ComponentType.CONNECTION) {
            EndpointChange endpointChange = endpointChange(baselineNode, labelComponent.getNode(), leaf.relPath());
            if (endpointChange != null) {
                builder.endpointChange(endpointChange);
            }
        }
        if (isPositionCoordinate(leaf.relPath())) {
            builder.positionChange(new PositionChange(
                    baselineNode.get(POSITION), labelComponent.getNode().get(POSITION)));
        }
        return builder.build();
    }

    private static boolean isPositionCoordinate(final List<String> relPath) {
        return relPath.size() == 2 && POSITION.equals(relPath.get(0));
    }

    private static EndpointChange endpointChange(final JsonNode baselineNode, final JsonNode targetNode,
            final List<String> relPath) {
        if (relPath.size() != 2 || !ID.equals(relPath.get(1)) || !ENDPOINT_ROLES.contains(relPath.get(0))) {
            return null;
        }
        String role = relPath.get(0);
        EndpointChange.EndpointRef baseline = endpointRef(baselineNode.get(role));
        EndpointChange.EndpointRef target = endpointRef(targetNode.get(role));
        if (baseline == null || target == null) {
            return null;
        }
        return new EndpointChange(role, baseline, target);
    }

    private static EndpointChange.EndpointRef endpointRef(final JsonNode endpoint) {
        if (endpoint == null || !endpoint.isObject()) {
            return null;
        }
        String type = text(endpoint, TYPE);
        String id = text(endpoint, ID);
        String name = text(endpoint, NAME);
        String label = name == null || name.isEmpty() ? id : name;
        String code = ComponentType.fromComponentType(type).map(ComponentType::getCode)
                .filter(value -> !value.isEmpty()).orElse(type);
        return new EndpointChange.EndpointRef(code, type, label, id);
    }

    private static List<GroupRef> ownGroupBreadcrumb(final IndexedComponent group) {
        if (group.isRoot()) {
            return List.of(new GroupRef(group.getName(), group.getIdentifier(), true, false));
        }
        List<GroupRef> breadcrumb = new ArrayList<>(group.getAncestors());
        breadcrumb.add(new GroupRef(group.getName(), group.getIdentifier(), false, group.isNameCollides()));
        return breadcrumb;
    }

    private void addWholeComponent(final IndexedComponent component, final String change,
            final Set<String> ownSideIds, final List<Difference> out) {
        List<String> segments = CanonicalPath.componentSegments(component);
        out.add(Difference.builder()
                .category(ChangeCategory.SIGNIFICANT)
                .change(change)
                .pathSegments(segments)
                .path(CanonicalPath.display(segments))
                .breadcrumb(breadcrumb(component))
                .shortLabel(ShortLabel.component(component))
                .componentType(component.getType())
                .identifier(component.getIdentifier())
                .name(component.getName())
                .build());
    }

    private static List<GroupRef> breadcrumb(final IndexedComponent component) {
        if (component.isRoot()) {
            return List.of(new GroupRef(component.getName(), component.getIdentifier(), true, false));
        }
        return component.getAncestors();
    }

    private static Set<String> excludedKeys(final ComponentType type) {
        if (type == ComponentType.PROCESS_GROUP) {
            return GROUP_COLLECTIONS;
        }
        if (type == ComponentType.REMOTE_PROCESS_GROUP) {
            return REMOTE_GROUP_COLLECTIONS;
        }
        return Set.of();
    }

    private static Set<String> union(final Set<String> a, final Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }
}
