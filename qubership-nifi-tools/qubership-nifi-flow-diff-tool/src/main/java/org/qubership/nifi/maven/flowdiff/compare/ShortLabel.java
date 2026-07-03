package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;
import org.qubership.nifi.maven.flowdiff.flow.IndexedComponent;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.DESTINATION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.ID;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.NAME;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.SOURCE;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.isEmpty;
import static org.qubership.nifi.maven.flowdiff.flow.JsonNodeUtils.textOrEmpty;

/**
 * Builds the shortened labels the text and Markdown renderers use. Identifiers are shortened to their first eight
 * characters and shown only when a same-kind (or, for a group, a sibling-group) name collision requires it. This is the
 * human-facing counterpart of {@link CanonicalPath}, which always carries the full identifier.
 */
public final class ShortLabel {

    private static final int SHORT_ID_LENGTH = 8;

    private ShortLabel() {
    }

    /**
     * Builds the short label of a component for the human renderers.
     *
     * @param component the component, labeled from the side it is present on
     * @return the short label
     */
    public static String component(final IndexedComponent component) {
        String name = component.getName();
        if (component.getType() == ComponentType.CONNECTION) {
            return connection(component.getNode(), name, component.getIdentifier(), component.isNameCollides());
        }
        if (component.getType() == ComponentType.PROCESS_GROUP) {
            //process group name is always non-empty
            return withOptionalId(name, component.getIdentifier(),
                    component.isNameCollides());
        }
        if (isEmpty(name)) {
            return shortId(component.getIdentifier());
        }
        return withOptionalId(name, component.getIdentifier(), component.isNameCollides());
    }

    /**
     * Builds the short label of an ancestor group for a breadcrumb.
     *
     * @param group the ancestor group reference
     * @return the short group label
     */
    public static String group(final GroupRef group) {
        if (group.root()) {
            //process group name is always non-empty:
            return group.name();
        }
        //process group name is always non-empty:
        return withOptionalId(group.name(), group.identifier(), group.needsId());
    }

    private static String connection(final JsonNode node, final String name,
                                     final String identifier, final boolean collides) {
        return (isEmpty(name) ? "" : name + " ") + "(" + shortId(identifier) + "): "
                + endpoint(node.get(SOURCE), collides) + " -> " + endpoint(node.get(DESTINATION), collides);
    }

    private static String endpoint(final JsonNode endpoint, final boolean collides) {
        if (endpoint == null) {
            return "?";
        }
        String name = textOrEmpty(endpoint, NAME);
        return isEmpty(name) ? textOrEmpty(endpoint, ID) : name;
    }

    private static String withOptionalId(final String base, final String identifier, final boolean collides) {
        return collides ? base + '(' + shortId(identifier) + ')' : base;
    }

    private static String shortId(final String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.length() <= SHORT_ID_LENGTH ? identifier : identifier.substring(0, SHORT_ID_LENGTH);
    }
}
