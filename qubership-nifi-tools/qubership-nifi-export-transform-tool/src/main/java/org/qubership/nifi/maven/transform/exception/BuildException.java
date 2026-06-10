package org.qubership.nifi.maven.transform.exception;

/**
 * Thrown when an error occurs during the Build operation.
 */
public class BuildException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a BuildException with the specified detail message.
     *
     * @param message the detail message
     */
    public BuildException(final String message) {
        super(message);
    }

    /**
     * Constructs a BuildException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public BuildException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
