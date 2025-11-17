package org.qubership.nifi.processors;

import org.apache.nifi.components.AllowableValue;

public enum SourceTypeValues {
    DYNAMIC_PROPERTY(new AllowableValue("dynamicProperties", "Dynamic Properties",
            "Create record from dynamic properties")),
    JSON_PROPERTY(new AllowableValue("jsonProperty", "Json Property",
            "Create record from the 'Json Property'"));

    private final AllowableValue allowableValue;

    SourceTypeValues(AllowableValue allowableValue) {
        this.allowableValue = allowableValue;
    }

    public AllowableValue getAllowableValue() {
        return allowableValue;
    }
}
