package org.qubership.nifi.maven.flowdiff.compare;

/**
 * A snapshot of a connection endpoint (source or destination) on both sides, attached to the endpoint {@code id}
 * difference when that {@code id} changed. It lets the text and Markdown renderers collapse a changed endpoint into a
 * single compact line instead of listing every endpoint field. Both the short type code and the full type name are
 * carried so each renderer can pick the form it needs: the code for text, the full name for Markdown.
 *
 * @param role     the endpoint role, {@code "source"} or {@code "destination"}
 * @param baseline the endpoint identity on the baseline side
 * @param target   the endpoint identity on the target side
 */
public record EndpointChange(String role, EndpointRef baseline, EndpointRef target) {

    /**
     * The identity of a connection endpoint on one side.
     *
     * @param typeCode   the short component type code (for example {@code OP}), for the text report
     * @param typeName   the full component type name (for example {@code OUTPUT_PORT}), for the Markdown report
     * @param label      the endpoint name, or its id when the name is empty
     * @param identifier the endpoint id
     */
    public record EndpointRef(String typeCode, String typeName, String label, String identifier) {
    }
}
