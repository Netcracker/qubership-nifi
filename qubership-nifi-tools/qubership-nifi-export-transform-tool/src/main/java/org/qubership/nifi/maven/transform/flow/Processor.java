package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A NiFi processor within a process group.
 * Property values are read from the "properties" JSON node, which contains
 * only explicitly set values. Properties with default or null values are absent.
 * If a property is absent from "properties", it means the user has not set it.
 * The caller receives an empty Optional and should report the absence.
 */
public class Processor {

    private final String name;
    private final String typeFqn;
    private final String identifier;
    private final ObjectNode propertiesNode;
    private final ProcessGroup parentGroup;

    public Processor(String name,
                     String typeFqn,
                     String identifier,
                     ObjectNode propertiesNode,
                     ProcessGroup parentGroup) {
        this.name = name;
        this.typeFqn = typeFqn;
        this.identifier = identifier;
        this.propertiesNode = propertiesNode;
        this.parentGroup = parentGroup;
    }

    /**
     * Finds a property by exact name among explicitly set properties.
     * Returns empty if the property is not present in "properties".
     *
     * @param propertyName exact property name
     * @return the property, or empty if not set
     */
    public Optional<ProcessorProperty> findProperty(String propertyName) {
        if (!propertiesNode.has(propertyName)) {
            return Optional.empty();
        }
        return Optional.of(new ProcessorProperty(propertyName, propertiesNode));
    }

    /**
     * Finds properties whose names match the given regular expression,
     * searching only among explicitly set properties.
     * Returns an empty list if no properties match.
     *
     * @param pattern compiled regex pattern
     * @return list of matching properties, may be empty
     */
    public List<ProcessorProperty> findPropertiesByRegex(Pattern pattern) {
        List<ProcessorProperty> result = new ArrayList<>();
        propertiesNode.fieldNames().forEachRemaining(name -> {
            if (pattern.matcher(name).matches()) {
                result.add(new ProcessorProperty(name, propertiesNode));
            }
        });
        return result;
    }

    public String getName() {
        return name;
    }

    public String getTypeFqn() {
        return typeFqn;
    }

    public String getIdentifier() {
        return identifier;
    }

    public ProcessGroup getParentGroup() {
        return parentGroup;
    }
}
