package org.qubership.nifi;

import java.util.Map;
import java.util.Objects;

public class ComponentProperties {

    private final String apiName;
    private final String displayName;
    private final String description;
    private Map<String, String> equivalentNameMappings;

    public ComponentProperties(String apiName, String displayName, String description) {
        this.apiName = apiName;
        this.displayName = displayName;
        this.description = description;
    }

    public String getApiName() {
        return apiName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Sets the dictionary mappings used by {@link #hasEquivalentName} and
     * {@link #getEquivalentName} for display-name equivalence checks.
     *
     * @param equivalentNameMappings map of lowercase display name to its equivalent name
     */
    public void setEquivalentNameMappings(Map<String, String> equivalentNameMappings) {
        this.equivalentNameMappings = equivalentNameMappings;
    }

    /**
     * Checks whether the dictionary contains an equivalent mapping
     * for the given display name.
     *
     * @param displayName the display name to look up
     * @return true if a mapping exists
     */
    public boolean hasEquivalentName(String displayName) {
        if (equivalentNameMappings == null || displayName == null) {
            return false;
        }
        return equivalentNameMappings.containsKey(displayName.toLowerCase());
    }

    /**
     * Returns the equivalent (mapped) name for the given display name
     * from the dictionary.
     *
     * @param displayName the display name to look up
     * @return the mapped equivalent name, or null if not found
     */
    public String getEquivalentName(String displayName) {
        if (equivalentNameMappings == null || displayName == null) {
            return null;
        }
        return equivalentNameMappings.get(displayName.toLowerCase());
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
        return false;
    }

    @Override
    public String toString() {
        return "ComponentProperties{"
                +
                "apiName='" + apiName
                + '\''
                + ", displayName='" + displayName
                + '\''
                + ", description='" + description
                + '\''
                + '}';
    }
}
