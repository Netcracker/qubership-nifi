package org.qubership.nifi.processors;

import org.apache.nifi.components.AllowableValue;

public enum SourceTypeValues {
    DYNAMIC_PROPERTY(new AllowableValue("dynamicProperty", "Dynamic Property",
            "Get record from a dynamic properties")),
    JSON_PROPERTY(new AllowableValue("jsonProperty", "Json Property",
            "Get record from a static json property"));

    private final AllowableValue allowableValue;

    SourceTypeValues(AllowableValue allowableValue) {
        this.allowableValue = allowableValue;
    }

    public AllowableValue getAllowableValue() {
        return allowableValue;
    }
}
