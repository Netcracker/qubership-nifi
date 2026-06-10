package org.qubership.nifi.maven.transform.exception;

public class ExtractException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs an ExtractException with the specified detail message.
     *
     * @param message the detail message
     */
    public ExtractException(final String message) {
        super(message);
    }

    /**
     * Constructs an ExtractException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public ExtractException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
