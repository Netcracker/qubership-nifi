package org.qubership.nifi.maven.flowdiff.flow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.CONNECTIONS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.CONTROLLER_SERVICES;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.FUNNELS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.INPUT_PORTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.LABELS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.NAME;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.OUTPUT_PORTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PROCESSORS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PROCESS_GROUPS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.REMOTE_PROCESS_GROUPS;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.text;

/**
 * The identity map of a flow export. The root process group is identified by location; every other child component is
 * indexed by its {@code identifier}, which NiFi keeps stable across exports. Building the index also enforces the
 * uniqueness assumption: a missing or duplicate component {@code identifier} fails the flow, naming the kind and value,
 * rather than risking a wrong match.
 */
public final class ComponentIndex {

    private static final Map<String, ComponentType> CHILD_COLLECTIONS = childCollections();

    private final IndexedComponent root;
    private final Map<String, IndexedComponent> byIdentifier;

    private ComponentIndex(final IndexedComponent rootValue, final Map<String, IndexedComponent> byIdentifierValue) {
        this.root = rootValue;
        this.byIdentifier = byIdentifierValue;
    }

    private static Map<String, ComponentType> childCollections() {
        Map<String, ComponentType> map = new LinkedHashMap<>();
        map.put(PROCESSORS, ComponentType.PROCESSOR);
        map.put(CONTROLLER_SERVICES, ComponentType.CONTROLLER_SERVICE);
        map.put(INPUT_PORTS, ComponentType.INPUT_PORT);
        map.put(OUTPUT_PORTS, ComponentType.OUTPUT_PORT);
        map.put(FUNNELS, ComponentType.FUNNEL);
        map.put(LABELS, ComponentType.LABEL);
        map.put(CONNECTIONS, ComponentType.CONNECTION);
        map.put(REMOTE_PROCESS_GROUPS, ComponentType.REMOTE_PROCESS_GROUP);
        map.put(PROCESS_GROUPS, ComponentType.PROCESS_GROUP);
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * Builds the identity map for a flow export.
     *
     * @param flow the parsed flow export
     * @return the index
     * @throws FlowParseException when a component carries a missing or duplicate {@code identifier}
     */
    public static ComponentIndex build(final FlowExport flow) {
        JsonNode flowContents = flow.getFlowContents();
        String display = flow.getDisplayPath();
        IndexedComponent rootComponent = new IndexedComponent(ComponentType.PROCESS_GROUP,
                text(flowContents, IDENTIFIER), text(flowContents, NAME), flowContents, List.of(), true, false);
        Map<String, IndexedComponent> map = new LinkedHashMap<>();
        List<GroupRef> rootAncestors = List.of(
                new GroupRef(rootComponent.getName(), rootComponent.getIdentifier(), true, false));
        indexGroup(flowContents, rootAncestors, map, display);
        return new ComponentIndex(rootComponent, map);
    }

    private static void indexGroup(final JsonNode group, final List<GroupRef> ancestors,
            final Map<String, IndexedComponent> map, final String display) {
        for (Map.Entry<String, ComponentType> entry : CHILD_COLLECTIONS.entrySet()) {
            indexArray(group.get(entry.getKey()), entry.getValue(), ancestors, map, display);
        }
    }

    private static void indexArray(final JsonNode array, final ComponentType type, final List<GroupRef> ancestors,
            final Map<String, IndexedComponent> map, final String display) {
        if (array == null || !array.isArray()) {
            return;
        }
        Map<String, Integer> nameCounts = countNames(array, type);
        for (JsonNode child : array) {
            register(child, type, ancestors, nameCounts, map, display);
        }
    }

    private static void register(final JsonNode child, final ComponentType type, final List<GroupRef> ancestors,
            final Map<String, Integer> nameCounts, final Map<String, IndexedComponent> map, final String display) {
        String identifier = text(child, IDENTIFIER);
        if (identifier == null || identifier.isEmpty()) {
            throw new FlowParseException("Component of kind " + type + " has a missing '" + IDENTIFIER
                    + "' in flow export: " + display);
        }
        String name = text(child, NAME);
        boolean collides = nameCounts.getOrDefault(collisionKey(type, name), 0) > 1;
        IndexedComponent component = new IndexedComponent(type, identifier, name, child, ancestors, false, collides);
        IndexedComponent previous = map.put(identifier, component);
        if (previous != null) {
            throw new FlowParseException("Duplicate component '" + IDENTIFIER + "' '" + identifier
                    + "' (kinds " + previous.getType() + " and " + type + ") in flow export: " + display);
        }
        if (type == ComponentType.PROCESS_GROUP) {
            List<GroupRef> childAncestors = new ArrayList<>(ancestors);
            childAncestors.add(new GroupRef(name, identifier, false, collides));
            indexGroup(child, childAncestors, map, display);
        } else if (type == ComponentType.REMOTE_PROCESS_GROUP) {
            // Remote ports are leaf components, so they keep the remote group's own ancestor chain (the enclosing
            // process group) rather than adding a group breadcrumb for the remote group itself.
            indexArray(child.get(INPUT_PORTS), ComponentType.REMOTE_INPUT_PORT, ancestors, map, display);
            indexArray(child.get(OUTPUT_PORTS), ComponentType.REMOTE_OUTPUT_PORT, ancestors, map, display);
        }
    }

    private static Map<String, Integer> countNames(final JsonNode array, final ComponentType type) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode child : array) {
            String key = collisionKey(type, text(child, NAME));
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static String collisionKey(final ComponentType type, final String name) {
        return type.name() + '|' + (name == null ? "" : name);
    }

    /**
     * Returns the root process group component.
     *
     * @return the root component
     */
    public IndexedComponent getRoot() {
        return root;
    }

    /**
     * Returns the map of non-root components keyed by {@code identifier}.
     *
     * @return the identity map
     */
    public Map<String, IndexedComponent> getByIdentifier() {
        return byIdentifier;
    }
}
