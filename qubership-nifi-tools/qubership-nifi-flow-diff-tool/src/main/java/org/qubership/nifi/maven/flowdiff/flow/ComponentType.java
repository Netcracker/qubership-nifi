package org.qubership.nifi.maven.flowdiff.flow;

import java.util.Optional;

/**
 * Kinds of NiFi flow components the tool matches and reports, together with the short type code used in the text
 * report. The enum name matches the NiFi {@code componentType} string, so {@link #fromComponentType(String)} maps an
 * export value onto a constant.
 */
public enum ComponentType {

    /** A processor. */
    PROCESSOR("P"),
    /** A controller service. */
    CONTROLLER_SERVICE("CS"),
    /** An input port. */
    INPUT_PORT("IP"),
    /** An output port. */
    OUTPUT_PORT("OP"),
    /** A funnel. */
    FUNNEL("FN"),
    /** A label. */
    LABEL("LB"),
    /** A remote process group. */
    REMOTE_PROCESS_GROUP("RPG"),
    /** A remote input port. */
    REMOTE_INPUT_PORT("RIP"),
    /** A remote output port. */
    REMOTE_OUTPUT_PORT("ROP"),
    /** A connection. */
    CONNECTION("CX"),
    /** A process group. Rendered as a breadcrumb, so it carries no text code. */
    PROCESS_GROUP(""),
    /** A parameter provider, a top-level sibling section entry. Carries no text code. */
    PARAMETER_PROVIDER("");

    private final String code;

    ComponentType(final String codeValue) {
        this.code = codeValue;
    }

    /**
     * Returns the short bracket code used in the text report (for example {@code P} for a processor).
     *
     * @return the type code, empty for a process group
     */
    public String getCode() {
        return code;
    }

    /**
     * Maps a NiFi {@code componentType} string onto a constant.
     *
     * @param componentType the NiFi component type string, may be {@code null}
     * @return the matching constant, or empty when the value is unknown or {@code null}
     */
    public static Optional<ComponentType> fromComponentType(final String componentType) {
        if (componentType == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(componentType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
