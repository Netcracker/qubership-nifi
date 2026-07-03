package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.FlowFields;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.DESTINATION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.NAME;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.SOURCE;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.TYPE;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodes.isEmpty;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodes.textOrEmpty;

/**
 * Builds the stable logical path of a component and of a field within a component. Segments are unescaped and never use
 * a JSON array index. The root process group is labeled by name, a nested group as {@code name(identifier)}, a leaf
 * component by name (with the identifier appended only on a same-kind name collision), and a connection by its full
 * endpoint form.
 */
public final class CanonicalPath {

    /** Token used for the root segment when the root process-group name is empty. */
    public static final String ROOT_FALLBACK = FlowFields.FLOW_CONTENTS;

    private CanonicalPath() {
    }

    /**
     * Builds the path segments locating a component, ending with the component's own segment.
     *
     * @param component the component, labeled from the side it is present on
     * @return the ordered, unescaped path segments
     */
    public static List<String> componentSegments(final IndexedComponent component) {
        List<String> segments = new ArrayList<>();
        if (component.isRoot()) {
            segments.add(rootSegment(component.getName()));
            return segments;
        }
        for (GroupRef ancestor : component.getAncestors()) {
            segments.add(groupSegment(ancestor));
        }
        segments.add(ownSegment(component));
        return segments;
    }

    /**
     * Appends a field path to a component's segments.
     *
     * @param componentSegments the component's own segments
     * @param fieldPath         the field path relative to the component, as unescaped segments
     * @return the combined path segments
     */
    public static List<String> withField(final List<String> componentSegments, final List<String> fieldPath) {
        List<String> segments = new ArrayList<>(componentSegments);
        segments.addAll(fieldPath);
        return segments;
    }

    /**
     * Joins path segments into the display path with {@code /} separators. No escaping is applied.
     *
     * @param segments the path segments
     * @return the display path
     */
    public static String display(final List<String> segments) {
        return String.join("/", segments);
    }

    private static String rootSegment(final String rootName) {
        return isEmpty(rootName) ? ROOT_FALLBACK : rootName;
    }

    private static String groupSegment(final GroupRef group) {
        if (group.root()) {
            return rootSegment(group.name());
        }
        return group.name() + '(' + group.identifier() + ')';
    }

    private static String ownSegment(final IndexedComponent component) {
        if (component.getType() == ComponentType.CONNECTION) {
            return connectionSegment(component);
        }
        if (component.getType() == ComponentType.PROCESS_GROUP) {
            return component.getName() + '(' + component.getIdentifier() + ')';
        }
        String name = component.getName();
        if (isEmpty(name)) {
            return component.getIdentifier();
        }
        return component.isNameCollides() ? name + '(' + component.getIdentifier() + ')' : name;
    }

    private static String connectionSegment(final IndexedComponent connection) {
        JsonNode node = connection.getNode();
        String source = endpoint(node.get(SOURCE));
        String destination = endpoint(node.get(DESTINATION));
        return source + "->" + destination + '(' + connection.getIdentifier() + ')';
    }

    private static String endpoint(final JsonNode endpoint) {
        if (endpoint == null) {
            return "?";
        }
        String id = textOrEmpty(endpoint, ID);
        String name = textOrEmpty(endpoint, NAME);
        String label = isEmpty(name) ? id : name;
        return label + '(' + textOrEmpty(endpoint, TYPE) + ':' + id + ')';
    }
}
