package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
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

    /**
     * Constructor for class Processor.
     *
     * @param nameValue           display name of the processor
     * @param typeFqnValue        fully qualified class name of the processor type
     * @param identifierValue     unique identifier (UUID) of the processor
     * @param propertiesNodeValue JSON object containing only explicitly set property values
     * @param parentGroupValue    process group that directly contains this processor
     */
    public Processor(final String nameValue,
                     final String typeFqnValue,
                     final String identifierValue,
                     final ObjectNode propertiesNodeValue,
                     final ProcessGroup parentGroupValue) {
        this.name = nameValue;
        this.typeFqn = typeFqnValue;
        this.identifier = identifierValue;
        this.propertiesNode = propertiesNodeValue;
        this.parentGroup = parentGroupValue;
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
        propertiesNode.fieldNames().forEachRemaining(propertyName -> {
            if (pattern.matcher(propertyName).matches()) {
                result.add(new ProcessorProperty(propertyName, propertiesNode));
            }
        });
        return result;
    }

    /**
     * Returns the display name of this processor.
     *
     * @return processor name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the fully qualified class name of this processor's type.
     *
     * @return processor type FQN
     */
    public String getTypeFqn() {
        return typeFqn;
    }

    /**
     * Returns the unique identifier (UUID) of this processor.
     *
     * @return processor identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Returns the process group that directly contains this processor.
     *
     * @return parent process group
     */
    public ProcessGroup getParentGroup() {
        return parentGroup;
    }

    /**
     * Returns the human-readable full path of this processor:
     * group segments from root to parent joined with " / ", followed by the processor name.
     * Example: "Extract / PutSQL_pg / MyProcessor"
     *
     * @return full display path
     */
    public String getFullPath() {
        List<String> segments = new ArrayList<>(parentGroup.getPathSegments());
        segments.add(name);
        return String.join(" / ", segments);
    }

    /**
     * Returns the relative file system path of this processor:
     * the parent group's relative path with the processor name appended.
     * Example: Extract/PutSQL_pg/MyProcessor
     *
     * @return relative Path from the flow root to this processor
     */
    public Path getRelativePath() {
        return parentGroup.getRelativePath().resolve(name);
    }
}
