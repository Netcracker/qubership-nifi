package org.qubership.nifi.flowdiff.error;

/**
 * Signals that a run could not be carried out although its inputs were acceptable: an unknown report format, a format
 * that needs an output file when none was given, or a failure while rendering or writing the report. Front ends
 * translate this into a Maven {@code MojoExecutionException} or a non-zero exit code.
 */
public class FlowDiffExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message describing what could not be carried out
     */
    public FlowDiffExecutionException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message describing what could not be carried out
     * @param cause   the underlying cause, typically an I/O error
     */
    public FlowDiffExecutionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
