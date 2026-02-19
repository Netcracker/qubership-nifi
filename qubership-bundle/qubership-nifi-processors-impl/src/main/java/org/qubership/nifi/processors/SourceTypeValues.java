package org.qubership.nifi.processors;

import org.apache.nifi.components.AllowableValue;

public enum SourceTypeValues {
    /**
     * dynamicProperties source type.
     */
    DYNAMIC_PROPERTY(new AllowableValue("dynamicProperties", "Dynamic Properties",
            "Create record from dynamic properties")),
    /**
     * jsonProperty source type.
     */
    JSON_PROPERTY(new AllowableValue("jsonProperty", "JSON Property",
            "Create record from the 'JSON Property'"));

    private final AllowableValue allowableValue;

    SourceTypeValues(final AllowableValue newAllowableValue) {
        this.allowableValue = newAllowableValue;
    }

    /**
     * Get allowable value.
     * @return allowableValue
     */
    public AllowableValue getAllowableValue() {
        return allowableValue;
    }
}
