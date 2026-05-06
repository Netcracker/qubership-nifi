package org.qubership.nifi.tools.openapi.enricher;

import java.io.IOException;
import java.io.InputStream;

/**
 * Utilities class to handle resources.
 */
public final class ResourceUtils {

    /**
     * Default private constructor.
     */
    private ResourceUtils() {
        //default private constructor
    }
    /**
     * Get resource as input stream from classpath.
     *
     * @param resourceName the resource name
     * @return input stream for the resource
     * @throws IOException if resource not found
     */
    public static InputStream getResourceAsStream(String resourceName) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        if (is == null) {
            throw new IOException("Resource not found: " + resourceName);
        }
        return is;
    }
}
