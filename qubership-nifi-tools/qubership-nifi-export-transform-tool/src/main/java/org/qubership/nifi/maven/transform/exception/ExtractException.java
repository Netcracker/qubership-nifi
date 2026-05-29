package org.qubership.nifi.maven.transform.exception;

public class ExtractException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExtractException(String message) {
        super(message);
    }

    public ExtractException(String message, Throwable cause) {
        super(message, cause);
    }
}
