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

/**
 * Compares two flow exports and produces the ordered list of {@link Difference} records for the {@code flowContents}
 * tree. Components are matched by identity, so array reordering never registers; the root process group is matched by
 * location. An added or removed component is a single significant change, and a whole added or removed subtree is
 * reported only at its top group, not repeated for every descendant.
 */
public final class FlowComparator {

    private static final String NAME = "name";
    private static final Set<String> GROUP_COLLECTIONS = Set.of(
            "processors", "controllerServices", "inputPorts", "outputPorts", "funnels", "labels",
            "connections", "remoteProcessGroups", "processGroups");
    private static final Set<String> REMOTE_GROUP_COLLECTIONS = Set.of("inputPorts", "outputPorts", "contents");

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
        List<Difference> out = new ArrayList<>();

        compareMatched(baselineIndex.getRoot(), targetIndex.getRoot(), out);

        Map<String, IndexedComponent> baseById = baselineIndex.getByIdentifier();
        Map<String, IndexedComponent> targetById = targetIndex.getByIdentifier();
        for (String id : union(baseById.keySet(), targetById.keySet())) {
            IndexedComponent base = baseById.get(id);
            IndexedComponent tgt = targetById.get(id);
            if (base != null && tgt != null) {
                compareMatched(base, tgt, out);
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
            final List<Difference> out) {
        NodeDiffer differ = new NodeDiffer(excludedKeys(target.getType()));
        for (LeafDiff leaf : differ.diff(base.getNode(), target.getNode())) {
            out.add(fieldDifference(target, leaf));
        }
    }

    private Difference fieldDifference(final IndexedComponent labelComponent, final LeafDiff leaf) {
        JsonNode context = leaf.target() != null ? labelComponent.getNode() : leaf.baseline();
        ChangeCategory category = ChangeCategorizer.categorize(labelComponent, leaf.relPath(), context);
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
        return builder.build();
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
        if (coveredByAncestor(component, ownSideIds)) {
            return;
        }
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

    private static boolean coveredByAncestor(final IndexedComponent component, final Set<String> ownSideIds) {
        for (GroupRef ancestor : component.getAncestors()) {
            if (!ancestor.root() && ownSideIds.contains(ancestor.identifier())) {
                return true;
            }
        }
        return false;
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

    private static String asText(final JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
