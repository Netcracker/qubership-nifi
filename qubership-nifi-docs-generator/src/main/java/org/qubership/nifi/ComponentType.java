package org.qubership.nifi;

/**
 * Enum for NiFi component type. One of processor, controller_service, reporting_task.
 */
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
