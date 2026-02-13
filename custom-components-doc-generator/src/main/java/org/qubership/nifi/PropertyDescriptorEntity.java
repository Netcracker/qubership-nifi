package org.qubership.nifi;

import org.apache.nifi.components.AllowableValue;

import java.util.List;
import java.util.stream.Collectors;

public class PropertyDescriptorEntity {

    private static final String HTTP_REGEX = "(https?://[^\\s]+)";

    private String displayName;
    private String apiName;
    private String defaultValue;
    private String description;
    private String componentDescription;
    private final List<AllowableValue> allowableValues;

    /**
     * Constructor for class CustomComponentEntity.
     *
     * @param displayNameValue display name value
     * @param apiNameValue api name value
     * @param defaultNameValue default name value
     * @param descriptionValue description value
     * @param allowableValuesValue allowable values value
     * @param componentDescriptionValue component description value
     */
    public PropertyDescriptorEntity(
            final String displayNameValue,
            final String apiNameValue,
            final String defaultNameValue,
            final String descriptionValue,
            final List<AllowableValue> allowableValuesValue,
            final String componentDescriptionValue) {
        this.displayName = displayNameValue;
        this.apiName = apiNameValue;
        this.defaultValue = defaultNameValue;
        this.description = descriptionValue;
        this.allowableValues = allowableValuesValue;
        this.componentDescription = componentDescriptionValue;
    }

    /**
     * Gets display name.
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets api name.
     * @return api name
     */
    public String getApiName() {
        return apiName;
    }

    /**
     * Gets default value.
     * @return default value
     */
    public String getDefaultValue() {
        return defaultValue;
    }


    /**
     * Gets default value as string.
     *
     * @return string default value
     */
    public String getDefaultValueAsString() {
        if (defaultValue == null || defaultValue.isEmpty()) {
            return "";
        }
        return escapeHttpLinks(defaultValue);
    }

    /**
     * Gets description.
     * @return description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets description as string.
     *
     * @return string description
     */
    public String getDescriptionAsString() {
        if (description == null || description.isEmpty()) {
            return "";
        }
        return escapeHttpLinks(description);
    }

    /**
     * Gets component description.
     * @return component description
     */
    public String getComponentDescription() {
        return componentDescription;
    }

    /**
     * Gets list allowable values.
     * @return list allowable values
     */
    public List<AllowableValue> getAllowableValues() {
        return allowableValues;
    }

    /**
     * Get list of allowable values in string format.
     *
     * @return allowable values in string format
     */
    public String getAllowableValuesAsString() {
        if (allowableValues == null || allowableValues.isEmpty()) {
            return "";
        }
        return allowableValues.stream()
                .map(AllowableValue::getDisplayName)
                .collect(Collectors.joining(", "));
    }

    /**
     * Sets display name.
     * @param newDisplayName
     */
    public void setDisplayName(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    /**
     * Sets api name.
     * @param newApiName
     */
    public void setApiName(String newApiName) {
        this.apiName = newApiName;
    }

    /**
     * Sets default value.
     * @param newDefaultName
     */
    public void setDefaultValue(String newDefaultName) {
        this.defaultValue = newDefaultName;
    }

    /**
     * Sets description.
     * @param newDescription
     */
    public void setDescription(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Sets component description.
     * @param newComponentDescription
     */
    public void setComponentDescription(String newComponentDescription) {
        this.componentDescription = newComponentDescription;
    }

    /**
     * Escapes HTTP and HTTPS links in a string by wrapping them with backticks.
     *
     * @param str the input string to process
     * @return string with escaped HTTP links, or the original string
     *  *         if it is {@code null}, empty, or contains no links
     */

    public String escapeHttpLinks(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }

        return str.replaceAll(HTTP_REGEX, "`$1`");
    }
}
