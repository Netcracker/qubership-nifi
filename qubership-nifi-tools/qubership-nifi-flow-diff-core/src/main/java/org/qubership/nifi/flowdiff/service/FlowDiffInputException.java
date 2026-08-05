package org.qubership.nifi.flowdiff.service;

/**
 * Signals that an input is unusable: a path that does not exist, a baseline and target that are not the same kind, or
 * an absolute path where only a relative one is accepted. The message names the offending path so the failure is
 * actionable. Front ends translate this into a Maven {@code MojoFailureException} or a non-zero exit code.
 */
public class FlowDiffInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message naming the offending path
     */
    public FlowDiffInputException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message naming the offending path
     * @param cause   the underlying cause
     */
    public FlowDiffInputException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
