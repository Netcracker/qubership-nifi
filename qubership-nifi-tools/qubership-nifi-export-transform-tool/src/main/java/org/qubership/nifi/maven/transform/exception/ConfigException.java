package org.qubership.nifi.maven.transform.exception;

public class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a ConfigException with the specified detail message.
     *
     * @param message the detail message
     */
    public ConfigException(final String message) {
        super(message);
    }

    /**
     * Constructs a ConfigException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public ConfigException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
