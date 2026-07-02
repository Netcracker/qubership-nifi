package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import java.util.List;

/**
 * Classifies a single leaf difference within a matched component into a {@link ChangeCategory}. Classification is by
 * JSON path and surrounding structure, never by bare field name, so an unrelated {@code version} inside a user property
 * or a {@code bundle}-named processor property is not misclassified. A connection endpoint {@code groupId} is technical
 * only when it is a back-reference to the root process group on both sides (its value equals the root identifier), so a
 * genuine sub-group port reference stays significant.
 */
public final class ChangeCategorizer {

    private static final String INSTANCE_IDENTIFIER = "instanceIdentifier";
    private static final String IDENTIFIER = "identifier";
    private static final String GROUP_IDENTIFIER = "groupIdentifier";
    private static final String BUNDLE = "bundle";
    private static final String VERSION = "version";
    private static final String SOURCE = "source";
    private static final String DESTINATION = "destination";
    private static final String GROUP_ID = "groupId";

    private ChangeCategorizer() {
    }

    /**
     * Classifies a leaf difference.
     *
     * @param owner          the matched component that owns the differing field
     * @param relPath        the field path relative to the owner component
     * @param context        the owner node on a side where the field is present, used for structural checks such as
     *                       bundle detection
     * @param baselineValue  the baseline value of the leaf, used to recognize root-group back-references
     * @param targetValue    the target value of the leaf, used to recognize root-group back-references
     * @param baselineRootId the baseline root process-group identifier
     * @param targetRootId   the target root process-group identifier
     * @return the category the difference belongs to
     */
    public static ChangeCategory categorize(final IndexedComponent owner, final List<String> relPath,
            final JsonNode context, final JsonNode baselineValue, final JsonNode targetValue,
            final String baselineRootId, final String targetRootId) {
        if (isOwnInstanceIdentifier(relPath) || isEndpointInstanceIdentifier(relPath)) {
            return ChangeCategory.TECHNICAL;
        }
        if (owner.isRoot() && isField(relPath, IDENTIFIER)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isDirectChildOfRoot(owner) && isField(relPath, GROUP_IDENTIFIER)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isEndpointGroupId(relPath) && refersToRoot(baselineValue, baselineRootId)
                && refersToRoot(targetValue, targetRootId)) {
            return ChangeCategory.TECHNICAL;
        }
        if (isBundleVersion(relPath, context)) {
            return ChangeCategory.ENVIRONMENTAL;
        }
        return ChangeCategory.SIGNIFICANT;
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
                && bundle.has("group") && bundle.has("artifact") && bundle.has(VERSION);
    }
}
