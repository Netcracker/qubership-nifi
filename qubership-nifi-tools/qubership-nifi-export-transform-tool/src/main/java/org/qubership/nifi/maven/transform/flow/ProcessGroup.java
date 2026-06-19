package org.qubership.nifi.maven.transform.flow;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A NiFi process group parsed from an exported flow JSON file.
 * May contain processors and nested process groups recursively.
 */
public class ProcessGroup {

    private final String name;
    private final String identifier;
    private final List<Processor> processors;
    private final List<ProcessGroup> children;

    /**
     * Parent group. Null for the root group (flowContents).
     */
    private final ProcessGroup parent;

    /**
     * True if this group references an external flow in NiFi Registry.
     * Such groups have empty processors and processGroups lists.
     */
    private final boolean versioned;

    /**
     * Constructor for class ProcessGroup.
     *
     * @param nameValue       display name of the group as shown in the NiFi UI
     * @param identifierValue unique UUID of the group within the flow
     * @param processorsList  processors directly contained in this group
     * @param childrenList    nested process groups directly contained in this group
     * @param parentValue     parent group, or null if this is the root group (flowContents)
     * @param versionedValue  true if this group is managed by NiFi Registry and its
     *                        content is not expanded in the export
     */
    public ProcessGroup(final String nameValue,
                        final String identifierValue,
                        final List<Processor> processorsList,
                        final List<ProcessGroup> childrenList,
                        final ProcessGroup parentValue,
                        final boolean versionedValue) {
        this.name = nameValue;
        this.identifier = identifierValue;
        this.processors = Collections.unmodifiableList(processorsList);
        this.children = Collections.unmodifiableList(childrenList);
        this.parent = parentValue;
        this.versioned = versionedValue;
    }

    /**
     * Returns the path segments from the root group to this group (root not included).
     *
     * @return ordered list of group name segments from root to this group
     */
    public List<String> getPathSegments() {
        List<String> segments = new ArrayList<>();
        ProcessGroup current = this;
        while (current.parent != null) {
            segments.add(0, current.name);
            current = current.parent;
        }
        return segments;
    }

    /**
     * Returns the relative path from the root group to this group as a Path object.
     * Returns an empty path if this is the root group.
     *
     * @return relative Path from root to this group
     */
    public Path getRelativePath() {
        List<String> segments = getPathSegments();
        if (segments.isEmpty()) {
            return Paths.get("");
        }
        return Paths.get(segments.get(0),
                segments.subList(1, segments.size()).toArray(String[]::new));
    }

    /**
     * Returns the display name of this group as it appears in the NiFi UI.
     *
     * @return group name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the unique identifier (UUID) of this group within the flow.
     *
     * @return group identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Returns the unmodifiable list of processors directly contained in this group.
     * Empty for versioned groups.
     *
     * @return processors in this group
     */
    public List<Processor> getProcessors() {
        return processors;
    }

    /**
     * Returns the unmodifiable list of child process groups directly contained in this group.
     * Empty for versioned groups.
     *
     * @return child groups
     */
    public List<ProcessGroup> getChildren() {
        return children;
    }

    /**
     * Returns the parent group, or null if this is the root group (flowContents).
     *
     * @return parent group, or null for the root
     */
    public ProcessGroup getParent() {
        return parent;
    }

    /**
     * Returns true if this group references an external flow in NiFi Registry.
     * Versioned groups have empty processor and child lists in the export.
     *
     * @return true if this group is versioned
     */
    public boolean isVersioned() {
        return versioned;
    }
}
