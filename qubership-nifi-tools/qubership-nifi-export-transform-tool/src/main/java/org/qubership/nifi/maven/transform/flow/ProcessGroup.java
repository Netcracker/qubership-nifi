package org.qubership.nifi.maven.transform.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A NiFi process group. May contain processors and nested process groups recursively.
 *
 */
public class ProcessGroup {

    private final String name;
    private final String identifier;
    private final List<Processor> processors;
    private final List<ProcessGroup> children;

    /**
     * Parent group.
     */
    private final ProcessGroup parent;

    /**
     * True if this group references an external flow in NiFi Registry.
     * Such groups have empty processors and processGroups lists.
     */
    private final boolean versioned;

    public ProcessGroup(String name,
                        String identifier,
                        List<Processor> processors,
                        List<ProcessGroup> children,
                        ProcessGroup parent,
                        boolean versioned) {
        this.name = name;
        this.identifier = identifier;
        this.processors = Collections.unmodifiableList(processors);
        this.children = Collections.unmodifiableList(children);
        this.parent = parent;
        this.versioned = versioned;
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

    public String getName() {
        return name;
    }

    public String getIdentifier() {
        return identifier;
    }

    public List<Processor> getProcessors() {
        return processors;
    }

    public List<ProcessGroup> getChildren() {
        return children;
    }

    public ProcessGroup getParent() {
        return parent;
    }

    public boolean isVersioned() {
        return versioned;
    }
}
