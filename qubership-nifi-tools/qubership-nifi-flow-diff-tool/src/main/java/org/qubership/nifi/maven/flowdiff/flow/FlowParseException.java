package org.qubership.nifi.maven.flowdiff.flow;

/**
 * Signals that a flow export could not be processed: malformed JSON, a duplicate or missing component
 * {@code identifier}, or an unknown top-level sibling section. The message names the offending file, kind, or section
 * so the failure is actionable. Callers translate this into a Maven {@code MojoFailureException}.
 */
public class FlowParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message naming the file, kind, or section at fault
     */
    public FlowParseException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message naming the file, kind, or section at fault
     * @param cause   the underlying cause, typically a JSON parse error
     */
    public FlowParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
