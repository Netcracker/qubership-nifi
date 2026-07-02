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
 * {@code instanceIdentifier}, the root {@code identifier}, and every direct child's {@code groupIdentifier}
 * back-reference - never by global value replacement, so a UUID-shaped value in a property or comment is left alone.
 */
public final class TechnicalReverter {

    private static final String INSTANCE_IDENTIFIER = "instanceIdentifier";
    private static final String IDENTIFIER = "identifier";
    private static final String GROUP_IDENTIFIER = "groupIdentifier";
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
        instance += revertEndpoints(workingById, committedById);

        String committedRootId = committedIndex.getRoot().getIdentifier();
        int root = restore(workingIndex.getRoot().getNode(), IDENTIFIER, committedRootId);

        int group = 0;
        for (IndexedComponent workingComponent : workingById.values()) {
            if (isDirectChildOfRoot(workingComponent)) {
                group += restore(workingComponent.getNode(), GROUP_IDENTIFIER, committedRootId);
            }
        }
        return new RevertCounts(instance, root, group);
    }

    private int revertEndpoints(final Map<String, IndexedComponent> workingById,
            final Map<String, IndexedComponent> committedById) {
        int reverted = 0;
        for (IndexedComponent working : workingById.values()) {
            if (working.getType() != ComponentType.CONNECTION) {
                continue;
            }
            reverted += revertEndpoint(working.getNode().get("source"), committedById);
            reverted += revertEndpoint(working.getNode().get("destination"), committedById);
        }
        return reverted;
    }

    private int revertEndpoint(final JsonNode endpoint, final Map<String, IndexedComponent> committedById) {
        if (endpoint == null || !endpoint.isObject()) {
            return 0;
        }
        IndexedComponent referenced = committedById.get(text(endpoint, ID));
        if (referenced == null) {
            return 0;
        }
        return restore(endpoint, INSTANCE_IDENTIFIER, text(referenced.getNode(), INSTANCE_IDENTIFIER));
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
