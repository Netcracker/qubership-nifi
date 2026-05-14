package org.qubership.cloud.nifi.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootTest(classes = {XmlConfigValidator.class,
        ConfigValidatorConfiguration.class})
@ImportAutoConfiguration
@ActiveProfiles("test2")
class XmlConfigValidatorTest {

    @Autowired
    private XmlConfigValidator validator;

    private Path conf = Paths.get(".", "conf");
    private Path mainConfDir = Paths.get(".", "persistent_conf", "conf");
    private Path restoreDir = Paths.get(".", "persistent_conf", "conf-restore");

    @BeforeEach
    void prepareDirectories() {
        //prepare test directories:
        try {
            Files.createDirectories(conf);
            Files.createDirectories(mainConfDir);
            Files.createDirectories(restoreDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dirs", e);
        }
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

    private String readResourceAsString(String resourceName) {
        String resourceValue = null;
        try {
            try (InputStream in = new BufferedInputStream(getResourceAsStream(resourceName))) {
                resourceValue = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Assertions.fail("Failed to read test resource: " + resourceName, e);
        }
        return resourceValue;
    }

    void copyResource(String resourceName, String targetFileName, Path targetPath) {
        try {
            Files.copy(getResourceAsStream(resourceName), targetPath.resolve(targetFileName));
        } catch (IOException e) {
            Assertions.fail("Failed to copy test resource " + resourceName
                    + " to temp path: " + targetPath.toString(), e);
        }
    }

    @Test
    void testSuccess() throws IOException, ParserConfigurationException {
        copyResource("xml/users.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations.xml", "authorizations.xml", restoreDir);
        validator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertFalse(restoreUsersXml.toFile().exists(),
                "users.xml does not exists in restore conf");
        Assertions.assertFalse(restoreAuthXml.toFile().exists(),
                "authorizations.xml does not exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));

    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(mainConfDir.resolve("users.xml"));
            Files.deleteIfExists(mainConfDir.resolve("authorizations.xml"));
            Files.deleteIfExists(restoreDir.resolve("users.xml"));
            Files.deleteIfExists(restoreDir.resolve("authorizations.xml"));
            Files.deleteIfExists(conf);
            Files.deleteIfExists(mainConfDir);
            Files.deleteIfExists(restoreDir);
            Files.deleteIfExists(Paths.get(".", "persistent_conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }
}
