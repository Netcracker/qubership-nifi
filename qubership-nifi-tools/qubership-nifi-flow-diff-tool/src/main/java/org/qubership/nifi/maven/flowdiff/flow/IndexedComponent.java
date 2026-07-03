package org.qubership.nifi.maven.flowdiff.flow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A component located within a flow export and prepared for matching and path building: its kind, identity, JSON node,
 * and the chain of ancestor process groups from the root down to its parent. Leaf components additionally carry a flag
 * telling whether a sibling of the same kind shares their name, so the path builder knows when to append an identifier.
 */
public final class IndexedComponent {

    private final ComponentType type;
    private final String identifier;
    private final String name;
    private final JsonNode node;
    private final List<GroupRef> ancestors;
    private final boolean root;
    private final boolean nameCollides;

    /**
     * Creates an indexed component.
     *
     * @param typeValue         the component kind
     * @param identifierValue   the component identifier (the root carries its identifier even though it is matched by
     *                          location)
     * @param nameValue         the component name (may be empty)
     * @param nodeValue         the component JSON node
     * @param ancestorsValue    the ancestor process groups, root first, parent last; empty for the root itself
     * @param rootValue         whether this is the root process group
     * @param nameCollidesValue whether a sibling of the same kind in the same parent group shares this name
     */
    public IndexedComponent(final ComponentType typeValue, final String identifierValue, final String nameValue,
            final JsonNode nodeValue, final List<GroupRef> ancestorsValue, final boolean rootValue,
            final boolean nameCollidesValue) {
        this.type = typeValue;
        this.identifier = identifierValue;
        this.name = nameValue;
        this.node = nodeValue;
        this.ancestors = List.copyOf(ancestorsValue);
        this.root = rootValue;
        this.nameCollides = nameCollidesValue;
    }

    /**
     * Returns the component kind.
     *
     * @return the type
     */
    public ComponentType getType() {
        return type;
    }

    /**
     * Returns the component identifier.
     *
     * @return the identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Returns the component name.
     *
     * @return the name, possibly empty
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the component JSON node.
     *
     * @return the node
     */
    public JsonNode getNode() {
        return node;
    }

    /**
     * Returns the ancestor process groups, root first and parent last.
     *
     * @return the immutable ancestor chain
     */
    public List<GroupRef> getAncestors() {
        return ancestors;
    }

    /**
     * Tells whether this is the root process group.
     *
     * @return {@code true} for the root
     */
    public boolean isRoot() {
        return root;
    }

    /**
     * Tells whether a sibling of the same kind in the same parent group shares this component's name, requiring the
     * path to append the identifier for disambiguation.
     *
     * @return {@code true} when the name collides with a same-kind sibling
     */
    public boolean isNameCollides() {
        return nameCollides;
    }

    /**
     * Determines, if this component is child of root process group.
     * @return true, if this component is child of root process group
     */
    public boolean isDirectChildOfRoot() {
        return this.getAncestors().size() == 1 && this.getAncestors().get(0).root();
    }
}
