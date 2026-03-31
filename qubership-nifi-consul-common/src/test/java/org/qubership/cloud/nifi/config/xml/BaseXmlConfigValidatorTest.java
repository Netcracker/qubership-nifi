package org.qubership.cloud.nifi.config.xml;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class BaseXmlConfigValidatorTest {
    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;
    private Path confDir;
    private Path mainConfDir;
    private Path restoreDir;
    private BaseXmlConfigValidator baseXmlConfigValidator;

    @BeforeEach
    void setUp() throws IOException {
        confDir = tempDir.resolve("conf");
        mainConfDir = tempDir.resolve("persistent-conf/conf");
        restoreDir = tempDir.resolve("restore");
        Files.createDirectories(confDir);
        Files.createDirectories(mainConfDir);
        Files.createDirectories(restoreDir);
        baseXmlConfigValidator = new BaseXmlConfigValidator(
                new XmlConfigValidatorConfig(confDir.toString(),
                        mainConfDir.toString(),
                        restoreDir.toString())
        );
    }

    /**
     * Get resource as input stream from classpath.
     *
     * @param resourceName the resource name
     * @return input stream for the resource
     * @throws IOException if resource not found
     */
    private InputStream getResourceAsStream(String resourceName) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        if (is == null) {
            throw new IOException("Resource not found: " + resourceName);
        }
        return is;
    }

    void copyResources(Path targetPath) {
        try {
            Files.copy(getResourceAsStream("xml/users.xml"),
                    targetPath.resolve("users.xml"));
            Files.copy(getResourceAsStream("xml/authorizations.xml"),
                    targetPath.resolve("authorizations.xml"));
        } catch (IOException e) {
            Assertions.fail("Failed to copy test resources to temp path: " + targetPath.toString(), e);
        }
    }

    @Test
    void testAllConfigurationsValid() throws IOException, ParserConfigurationException {
        copyResources(mainConfDir);
        baseXmlConfigValidator.validate();
    }
}
