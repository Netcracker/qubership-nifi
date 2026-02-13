package org.qubership.nifi;

public enum ComponentType {

    PROCESSOR("processor"),
    CONTROLLER_SERVICE("controller_service"),
    REPORTING_TASK("reporting_task");

    private final String type;

    ComponentType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
