package org.qubership.nifi.tools.compare;

import java.util.Map;
import java.util.Objects;

public class ComponentProperties {

    private final String apiName;
    private final String displayName;
    private final String description;
    private Map<String, String> equivalentNameMappings;

    /**
     * Constructor for class ComponentProperties.
     *
     * @param apiNameValue property api name
     * @param displayNameValue property display name
     * @param descriptionValue property description
     */
    public ComponentProperties(
            final String apiNameValue,
            final String displayNameValue,
            final String descriptionValue
    ) {
        this.apiName = apiNameValue;
        this.displayName = displayNameValue;
        this.description = descriptionValue;
    }

    /**
     * Gets api name.
     * @return api name
     */
    public String getApiName() {
        return apiName;
    }

    /**
     * Gets display name.
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets description.
     * @return description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the dictionary mappings used by {@link #hasEquivalentName} and
     * {@link #getEquivalentName} for display-name equivalence checks.
     *
     * @param equivalentNameMappingsValue map of lowercase display name to its equivalent name
     */
    public void setEquivalentNameMappings(Map<String, String> equivalentNameMappingsValue) {
        this.equivalentNameMappings = equivalentNameMappingsValue;
    }

    /**
     * Checks whether the dictionary contains an equivalent mapping
     * for the given display name.
     *
     * @param displayNameValue the display name to look up
     * @return true if a mapping exists
     */
    public boolean hasEquivalentName(String displayNameValue) {
        if (equivalentNameMappings == null || displayNameValue == null) {
            return false;
        }
        return equivalentNameMappings.containsKey(displayNameValue.toLowerCase());
    }

    /**
     * Returns the equivalent (mapped) name for the given display name
     * from the dictionary.
     *
     * @param displayNameValue the display name to look up
     * @return the mapped equivalent name, or null if not found
     */
    public String getEquivalentName(String displayNameValue) {
        if (equivalentNameMappings == null || displayNameValue == null) {
            return null;
        }
        return equivalentNameMappings.get(displayNameValue.toLowerCase());
    }

    /**
     * Comparison strategy for properties whose display name is unique
     * within the component.
     *
     * @param other the other property to compare with
     * @return true if the properties match by any of the above criteria
     */
    public boolean compareUniqueDisplayName(ComponentProperties other) {
        if (other == null) {
            return false;
        }
        if (Objects.equals(this.apiName, other.apiName)) {
            return true;
        }
        if (Objects.equals(this.displayName, other.displayName)) {
            return true;
        }
        if (this.displayName != null && other.displayName != null
                && this.displayName.equalsIgnoreCase(other.displayName)) {
            return true;
        }
        if (hasEquivalentName(other.displayName)
                && Objects.equals(this.displayName, getEquivalentName(other.displayName))) {
            return true;
        }
        if (hasEquivalentName(this.displayName)
                && Objects.equals(other.displayName, getEquivalentName(this.displayName))) {
            return true;
        }
        return false;
    }

    /**
     * Comparison strategy for properties whose display name is NOT unique
     * within the component.
     *
     * @param other the other property to compare with
     * @return true if the properties match by any of the above criteria
     */
    public boolean compareNonUniqueDisplayName(ComponentProperties other) {
        if (other == null) {
            return false;
        }
        if (Objects.equals(this.apiName, other.apiName)) {
            return true;
        }
        boolean descriptionsEqual = Objects.equals(this.description, other.description);

        if (Objects.equals(this.displayName, other.displayName) && descriptionsEqual) {
            return true;
        }
        if (this.displayName != null && other.displayName != null
                && this.displayName.equalsIgnoreCase(other.displayName) && descriptionsEqual) {
            return true;
        }
        if (hasEquivalentName(other.displayName)
                && Objects.equals(this.displayName, getEquivalentName(other.displayName))
                && descriptionsEqual) {
            return true;
        }
        if (hasEquivalentName(this.displayName)
                && Objects.equals(other.displayName, getEquivalentName(this.displayName))
                && descriptionsEqual) {
            return true;
        }
        return false;
    }
}
