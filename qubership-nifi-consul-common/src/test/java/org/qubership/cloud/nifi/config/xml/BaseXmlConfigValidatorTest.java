package org.qubership.cloud.nifi.config.xml;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class BaseXmlConfigValidatorTest {
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
    void testAllConfigurationsValid() throws IOException, ParserConfigurationException {
        copyResource("xml/users.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
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

    @Test
    void testMainAuthorizationsMissing() throws IOException, ParserConfigurationException {
        copyResource("xml/users.xml", "users.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testMainUsersMissing() throws IOException, ParserConfigurationException {
        copyResource("xml/authorizations.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testMainEmpty() throws IOException, ParserConfigurationException {
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testInvalidUsersXml() throws IOException, ParserConfigurationException {
        copyResource("xml/users-invalid.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testInvalidAuthorizationsXml() throws IOException, ParserConfigurationException {
        copyResource("xml/users.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations-invalid.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testInvalidUsersAndAuthorizationsXml() throws IOException, ParserConfigurationException {
        copyResource("xml/users-invalid.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations-invalid.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
        Path usersXml = mainConfDir.resolve("users.xml");
        Path authXml = mainConfDir.resolve("authorizations.xml");
        Path restoreUsersXml = restoreDir.resolve("users.xml");
        Path restoreAuthXml = restoreDir.resolve("authorizations.xml");
        Assertions.assertTrue(restoreUsersXml.toFile().exists(),
                "users.xml exists in restore conf");
        Assertions.assertTrue(restoreAuthXml.toFile().exists(),
                "authorizations.xml exists in restore conf");
        Assertions.assertTrue(usersXml.toFile().exists(), "users.xml exists in main conf");
        Assertions.assertTrue(authXml.toFile().exists(), "authorizations.xml exists in main conf");
        Assertions.assertEquals(readResourceAsString("xml/users-restore.xml"),
                Files.readString(usersXml, StandardCharsets.UTF_8));
        Assertions.assertEquals(readResourceAsString("xml/authorizations-restore.xml"),
                Files.readString(authXml, StandardCharsets.UTF_8));
    }

    @Test
    void testPathsWithTrailingSeparator() throws IOException, ParserConfigurationException {
        //init config validator with paths
        baseXmlConfigValidator = new BaseXmlConfigValidator(
                new XmlConfigValidatorConfig(confDir.toString() + File.separator,
                        mainConfDir.toString() + File.separator,
                        restoreDir.toString() + File.separator)
        );
        copyResource("xml/users.xml", "users.xml", mainConfDir);
        copyResource("xml/authorizations.xml", "authorizations.xml", mainConfDir);
        copyResource("xml/users-restore.xml", "users.xml", restoreDir);
        copyResource("xml/authorizations-restore.xml", "authorizations.xml", restoreDir);
        baseXmlConfigValidator.validate();
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
}
