package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single property of a NiFi processor.
 */
public final class ProcessorProperty {

    private final String name;

    /**
     * Direct reference to the "properties" ObjectNode of the parent processor.
     */
    private final ObjectNode propertiesNode;

    /**
     * Constructs a ProcessorProperty for the given property name within a properties node.
     *
     * @param nameValue        name of the property
     * @param propertiesNodeValue the "properties" ObjectNode of the parent processor
     */
    public ProcessorProperty(final String nameValue, final ObjectNode propertiesNodeValue) {
        this.name = nameValue;
        this.propertiesNode = propertiesNodeValue;
    }

    /**
     * Returns the name of this property.
     *
     * @return property name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the current value of the property.
     *
     * @return current string value, or null if the property is absent or JSON null
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
     *
     * @return true if the value starts with "@", false otherwise
     */
    public boolean isReference() {
        String value = getValue();
        return value != null && value.startsWith("@");
    }

    /**
     * Returns the path from the reference (everything after "@").
     *
     * @return reference path string without the leading "@"
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
     *
     * @return true if the value is null or blank
     */
    public boolean isEmpty() {
        String value = getValue();
        return value == null || value.isBlank();
    }
}
