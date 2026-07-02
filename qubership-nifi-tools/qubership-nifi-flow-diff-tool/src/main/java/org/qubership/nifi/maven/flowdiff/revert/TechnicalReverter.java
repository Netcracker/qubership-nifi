package org.qubership.nifi.maven.flowdiff.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentIndex;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.Map;

/**
 * Restores the technical fields of a working flow to their committed values, mutating the working tree in place. Only
 * typed, known-technical locations are written - a component's own {@code instanceIdentifier}, a connection endpoint's
 * {@code instanceIdentifier}, the root {@code identifier}, every direct child's {@code groupIdentifier} back-reference,
 * and a connection endpoint's {@code groupId} when it is a root back-reference - never by global value replacement, so
 * a UUID-shaped value in a property or comment is left alone.
 */
public final class TechnicalReverter {

    private static final String INSTANCE_IDENTIFIER = "instanceIdentifier";
    private static final String IDENTIFIER = "identifier";
    private static final String GROUP_IDENTIFIER = "groupIdentifier";
    private static final String GROUP_ID = "groupId";
    private static final String ID = "id";

    /**
     * Reverts the technical fields of a working flow to their committed values.
     *
     * @param committed the committed (baseline) flow
     * @param working   the working flow whose tree is mutated in place
     * @return the counts of reverted technical changes by kind
     */
    public RevertCounts revert(final FlowExport committed, final FlowExport working) {
        ComponentIndex committedIndex = ComponentIndex.build(committed);
        ComponentIndex workingIndex = ComponentIndex.build(working);
        Map<String, IndexedComponent> committedById = committedIndex.getByIdentifier();
        Map<String, IndexedComponent> workingById = workingIndex.getByIdentifier();

        String committedRootId = committedIndex.getRoot().getIdentifier();
        String workingRootId = workingIndex.getRoot().getIdentifier();

        int instance = 0;
        for (Map.Entry<String, IndexedComponent> entry : workingById.entrySet()) {
            IndexedComponent committedComponent = committedById.get(entry.getKey());
            if (committedComponent != null) {
                instance += restore(entry.getValue().getNode(), INSTANCE_IDENTIFIER,
                        text(committedComponent.getNode(), INSTANCE_IDENTIFIER));
            }
        }
        instance += restore(workingIndex.getRoot().getNode(), INSTANCE_IDENTIFIER,
                text(committedIndex.getRoot().getNode(), INSTANCE_IDENTIFIER));

        int endpointGroupId = 0;
        for (IndexedComponent connection : workingById.values()) {
            if (connection.getType() != ComponentType.CONNECTION) {
                continue;
            }
            JsonNode source = connection.getNode().get("source");
            JsonNode destination = connection.getNode().get("destination");
            instance += revertEndpointInstance(source, committedById);
            instance += revertEndpointInstance(destination, committedById);
            endpointGroupId += revertEndpointGroupId(source, workingRootId, committedRootId);
            endpointGroupId += revertEndpointGroupId(destination, workingRootId, committedRootId);
        }

        int root = restore(workingIndex.getRoot().getNode(), IDENTIFIER, committedRootId);

        int group = 0;
        for (IndexedComponent workingComponent : workingById.values()) {
            if (isDirectChildOfRoot(workingComponent)) {
                group += restore(workingComponent.getNode(), GROUP_IDENTIFIER, committedRootId);
            }
        }
        return new RevertCounts(instance, root, group, endpointGroupId);
    }

    private int revertEndpointInstance(final JsonNode endpoint, final Map<String, IndexedComponent> committedById) {
        if (endpoint == null || !endpoint.isObject()) {
            return 0;
        }
        IndexedComponent referenced = committedById.get(text(endpoint, ID));
        if (referenced == null) {
            return 0;
        }
        return restore(endpoint, INSTANCE_IDENTIFIER, text(referenced.getNode(), INSTANCE_IDENTIFIER));
    }

    private int revertEndpointGroupId(final JsonNode endpoint, final String workingRootId,
            final String committedRootId) {
        if (endpoint == null || !endpoint.isObject() || !workingRootId.equals(text(endpoint, GROUP_ID))) {
            return 0;
        }
        return restore(endpoint, GROUP_ID, committedRootId);
    }

    private static int restore(final JsonNode node, final String field, final String committedValue) {
        if (committedValue == null || !(node instanceof ObjectNode object)) {
            return 0;
        }
        if (committedValue.equals(text(node, field))) {
            return 0;
        }
        object.put(field, committedValue);
        return 1;
    }

    private static boolean isDirectChildOfRoot(final IndexedComponent component) {
        return component.getAncestors().size() == 1 && component.getAncestors().get(0).root();
    }

    private static String text(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
