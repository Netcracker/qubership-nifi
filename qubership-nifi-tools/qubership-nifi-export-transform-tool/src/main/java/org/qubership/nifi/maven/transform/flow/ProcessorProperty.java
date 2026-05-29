package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single property of a NiFi processor.
 */
public class ProcessorProperty {

    private final String name;

    /**
     * Direct reference to the "properties" ObjectNode of the parent processor.
     */
    private final ObjectNode propertiesNode;

    public ProcessorProperty(String name, ObjectNode propertiesNode) {
        this.name = name;
        this.propertiesNode = propertiesNode;
    }

    public String getName() {
        return name;
    }

    /**
     * Returns the current value of the property.
     */
    public String getValue() {
        if (propertiesNode.has(name)) {
            return propertiesNode.get(name).isNull()
                    ? null
                    : propertiesNode.get(name).asText();
        }
        return null;
    }

    /**
     * Sets the property value in-place in the JSON tree.
     *
     * @param newValue new property value
     */
    public void setValue(String newValue) {
        propertiesNode.put(name, newValue);
    }

    /**
     * Returns true if the value is a file reference (starts with "@").
     */
    public boolean isReference() {
        String value = getValue();
        return value != null && value.startsWith("@");
    }

    /**
     * Returns the path from the reference (everything after "@").
     *
     * @throws IllegalStateException if the property is not a reference
     */
    public String getReferencePath() {
        if (!isReference()) {
            throw new IllegalStateException(
                    "Property '" + name + "' is not a reference, value: " + getValue());
        }
        return getValue().substring(1);
    }

    /**
     * Returns true if the value is empty (null or blank string).
     */
    public boolean isEmpty() {
        String value = getValue();
        return value == null || value.isBlank();
    }
}
