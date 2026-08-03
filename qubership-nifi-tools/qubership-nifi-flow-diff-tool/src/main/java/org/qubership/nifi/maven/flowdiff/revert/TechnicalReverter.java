package org.qubership.nifi.maven.flowdiff.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentIndex;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.Map;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.DESTINATION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.GROUP_ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.GROUP_IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.INSTANCE_IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.SOURCE;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.text;

/**
 * Restores the technical fields of a working flow to their committed values, mutating the working tree in place. Only
 * typed, known-technical locations are written - a component's own {@code instanceIdentifier}, a connection endpoint's
 * {@code instanceIdentifier} when the endpoint {@code id} is unchanged, the root {@code identifier}, every direct
 * child's {@code groupIdentifier} back-reference, and a connection endpoint's {@code groupId} when it is a root
 * back-reference - never by global value replacement, so a UUID-shaped value in a property or comment is left alone. An
 * endpoint whose {@code id} changed points to a different component, so its {@code instanceIdentifier} is a significant
 * change and is left in place.
 */
public final class TechnicalReverter {

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
        int endpointGroupId = 0;
        int group = 0;
        // The three technical fields below live in disjoint locations, so a single pass over the working components
        // suffices. The map is keyed by identifier, so one committed lookup per entry serves both the component's own
        // instanceIdentifier and, for a connection, its endpoint handling.
        for (Map.Entry<String, IndexedComponent> entry : workingById.entrySet()) {
            IndexedComponent workingComponent = entry.getValue();
            IndexedComponent committedComponent = committedById.get(entry.getKey());
            if (committedComponent != null) {
                instance += restore(workingComponent.getNode(), INSTANCE_IDENTIFIER,
                        text(committedComponent.getNode(), INSTANCE_IDENTIFIER));
            }
            if (workingComponent.getType() == ComponentType.CONNECTION) {
                JsonNode source = workingComponent.getNode().get(SOURCE);
                JsonNode destination = workingComponent.getNode().get(DESTINATION);
                instance += revertEndpointInstance(source, committedEndpoint(committedComponent, SOURCE));
                instance += revertEndpointInstance(destination, committedEndpoint(committedComponent, DESTINATION));
                if (workingComponent.isDirectChildOfRoot()) {
                    //groupId in endpoints should be reverted only for connections under root PG
                    endpointGroupId += revertEndpointGroupId(source, workingRootId, committedRootId);
                    endpointGroupId += revertEndpointGroupId(destination, workingRootId, committedRootId);
                }
            }
            if (workingComponent.isDirectChildOfRoot()) {
                group += restore(workingComponent.getNode(), GROUP_IDENTIFIER, committedRootId);
            }
        }
        instance += restore(workingIndex.getRoot().getNode(), INSTANCE_IDENTIFIER,
                text(committedIndex.getRoot().getNode(), INSTANCE_IDENTIFIER));

        int root = restore(workingIndex.getRoot().getNode(), IDENTIFIER, committedRootId);

        return new RevertCounts(instance, root, group, endpointGroupId);
    }

    private int revertEndpointInstance(final JsonNode workingEndpoint, final JsonNode committedEndpoint) {
        if (workingEndpoint == null || !workingEndpoint.isObject()
                || committedEndpoint == null || !committedEndpoint.isObject()) {
            return 0;
        }
        String workingId = text(workingEndpoint, ID);
        if (workingId == null || !workingId.equals(text(committedEndpoint, ID))) {
            return 0;
        }
        return restore(workingEndpoint, INSTANCE_IDENTIFIER, text(committedEndpoint, INSTANCE_IDENTIFIER));
    }

    private static JsonNode committedEndpoint(final IndexedComponent committedConnection, final String role) {
        return committedConnection == null ? null : committedConnection.getNode().get(role);
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
}
