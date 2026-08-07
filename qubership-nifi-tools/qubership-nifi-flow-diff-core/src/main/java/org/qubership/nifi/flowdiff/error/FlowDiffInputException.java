package org.qubership.nifi.flowdiff.error;

/**
 * Signals that an input is unusable before any flow content is read: a path that does not exist, a baseline and target
 * that are not the same kind, an absolute path where only a relative one is accepted, a path that resolves outside the
 * enclosing Git worktree, or a revision that cannot be resolved. The message names the offending path or revision so
 * the failure is actionable. Front ends translate this into a Maven {@code MojoFailureException} or a non-zero exit
 * code.
 */
public class FlowDiffInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message naming the offending path or revision
     */
    public FlowDiffInputException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message naming the offending path or revision
     * @param cause   the underlying cause
     */
    public FlowDiffInputException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
