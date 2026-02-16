package org.qubership.nifi;

public enum ComponentType {

    /**
     * processor type.
     */
    PROCESSOR("processor"),
    /**
     * controller service type.
     */
    CONTROLLER_SERVICE("controller_service"),
    /**
     * reporting task type.
     */
    REPORTING_TASK("reporting_task");

    private final String type;

    ComponentType(final String typeValue) {
        this.type = typeValue;
    }
}
