package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.List;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ARTIFACT;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.BUNDLE;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.DESTINATION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.GROUP;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.GROUP_ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.GROUP_IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.INSTANCE_IDENTIFIER;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.SOURCE;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.VERSION;

/**
 * Classifies a single leaf difference within a matched component into a {@link ChangeCategory}. Classification is by
 * JSON path and surrounding structure, never by bare field name, so an unrelated {@code version} inside a user property
 * or a {@code bundle}-named processor property is not misclassified. A connection endpoint {@code groupId} is technical
 * only when it is a back-reference to the root process group on both sides (its value equals the root identifier), so a
 * genuine sub-group port reference stays significant. A connection endpoint {@code instanceIdentifier} is technical
 * only when the endpoint {@code id} is unchanged (the same referenced component); when the {@code id} changes the
 * endpoint points to a different component, so every endpoint field is a significant change.
 */
public final class ChangeCategorizer {

    private ChangeCategorizer() {
    }

    /**
     * Classifies a leaf difference.
     *
     * @param owner          the matched component that owns the differing field
     * @param relPath        the field path relative to the owner component
     * @param baselineNode   the owner node on the baseline side, or {@code null} when the field is target-only
     * @param targetNode     the owner node on the target side, or {@code null} when the field is baseline-only
     * @param baselineRootId the baseline root process-group identifier
     * @param targetRootId   the target root process-group identifier
     * @return the category the difference belongs to
     */
    public static ChangeCategory categorize(final IndexedComponent owner, final List<String> relPath,
            final JsonNode baselineNode, final JsonNode targetNode, final String baselineRootId,
            final String targetRootId) {
        if (isOwnInstanceIdentifier(relPath)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isEndpointInstanceIdentifier(relPath) && endpointIdUnchanged(relPath, baselineNode, targetNode)) {
            return ChangeCategory.TECHNICAL;
        }
        if (owner.isRoot() && isField(relPath, IDENTIFIER)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isDirectChildOfRoot(owner) && isField(relPath, GROUP_IDENTIFIER)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isEndpointGroupId(relPath) && refersToRoot(valueAt(baselineNode, relPath), baselineRootId)
                && refersToRoot(valueAt(targetNode, relPath), targetRootId)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isBundleVersion(relPath, targetNode != null ? targetNode : baselineNode)) {
            return ChangeCategory.ENVIRONMENTAL;
        }
        return ChangeCategory.SIGNIFICANT;
    }

    private static boolean endpointIdUnchanged(final List<String> relPath, final JsonNode baselineNode,
            final JsonNode targetNode) {
        JsonNode baselineId = endpointId(baselineNode, relPath.get(0));
        JsonNode targetId = endpointId(targetNode, relPath.get(0));
        return baselineId != null && targetId != null && baselineId.equals(targetId);
    }

    private static JsonNode endpointId(final JsonNode ownerNode, final String role) {
        if (ownerNode == null) {
            return null;
        }
        JsonNode endpoint = ownerNode.get(role);
        return endpoint == null ? null : endpoint.get(ID);
    }

    private static JsonNode valueAt(final JsonNode ownerNode, final List<String> relPath) {
        JsonNode value = ownerNode;
        for (int i = 0; i < relPath.size() && value != null; i++) {
            value = value.get(relPath.get(i));
        }
        return value;
    }

    private static boolean isField(final List<String> relPath, final String field) {
        return relPath.size() == 1 && field.equals(relPath.get(0));
    }

    private static boolean isOwnInstanceIdentifier(final List<String> relPath) {
        return isField(relPath, INSTANCE_IDENTIFIER);
    }

    private static boolean isEndpointInstanceIdentifier(final List<String> relPath) {
        return relPath.size() == 2 && INSTANCE_IDENTIFIER.equals(relPath.get(1))
                && (SOURCE.equals(relPath.get(0)) || DESTINATION.equals(relPath.get(0)));
    }

    private static boolean isEndpointGroupId(final List<String> relPath) {
        return relPath.size() == 2 && GROUP_ID.equals(relPath.get(1))
                && (SOURCE.equals(relPath.get(0)) || DESTINATION.equals(relPath.get(0)));
    }

    private static boolean refersToRoot(final JsonNode value, final String rootId) {
        return value != null && !value.isNull() && rootId != null && rootId.equals(value.asText());
    }

    private static boolean isDirectChildOfRoot(final IndexedComponent owner) {
        return owner.getAncestors().size() == 1 && owner.getAncestors().get(0).root();
    }

    private static boolean isBundleVersion(final List<String> relPath, final JsonNode context) {
        int size = relPath.size();
        if (size < 2 || !VERSION.equals(relPath.get(size - 1)) || !BUNDLE.equals(relPath.get(size - 2))) {
            return false;
        }
        JsonNode bundle = context;
        for (int i = 0; i < size - 1 && bundle != null; i++) {
            bundle = bundle.get(relPath.get(i));
        }
        return isNifiBundle(bundle);
    }

    private static boolean isNifiBundle(final JsonNode bundle) {
        return bundle != null && bundle.isObject()
                && bundle.has(GROUP) && bundle.has(ARTIFACT) && bundle.has(VERSION);
    }
}
