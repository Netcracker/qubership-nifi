package org.qubership.cloud.nifi.config.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

class BasePropertiesManagerTest {
    private static final Logger LOG = LoggerFactory.getLogger(BasePropertiesManagerTest.class);
    private BasePropertiesManager pm;
    private TestPropertiesProvider propertiesProvider;

    private void initBasePropertiesManager() {
        initBasePropertiesManager("logback-template.xml", "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "nifi.registry", Set.of("")
        );
    }

    private void initBasePropertiesManager(String logbackTemplate, String defaultProperties,
                                           String configFilePath,
                                           String configFileName, String propertyPrefix,
                                           Set<String> readonlyPropertyNames) {
        initBasePropertiesManager(logbackTemplate, defaultProperties, configFilePath,
                configFileName, propertyPrefix, readonlyPropertyNames, null);
    }

    private void initBasePropertiesManager(String logbackTemplate, String defaultProperties,
                                           String configFilePath,
                                           String configFileName, String propertyPrefix,
                                           Set<String> readonlyPropertyNames,
                                           Set<String> customPropertyNames) {
        this.propertiesProvider = new TestPropertiesProvider();
        this.pm = new BasePropertiesManager(new BasePropertiesManagerConfig(
                logbackTemplate,
                defaultProperties,
                "nifi_registry_internal.properties",
                "nifi_registry_internal_comments.properties",
                configFilePath,
                configFileName,
                propertyPrefix,
                readonlyPropertyNames,
                "custom.properties",
                customPropertyNames,
                propertiesProvider
        ));
    }

    @BeforeEach
    void init() {
        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }

    private Map<String, String> parseLogbackConfig(String fileName) {
        Map<String, String> loggerLevels = new HashMap<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            //configure to avoid XXE attacks:
            dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbFactory.setXIncludeAware(false);
            dbFactory.setExpandEntityReferences(false);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = null;
            try (InputStream is = new BufferedInputStream(new FileInputStream(fileName))) {
                doc = dBuilder.parse(is);
            }
            doc.getDocumentElement().normalize();
            NodeList loggersList = doc.getElementsByTagName("logger");
            for (int i = 0; i < loggersList.getLength(); i++) {
                Node prop = loggersList.item(i);
                NamedNodeMap attr = prop.getAttributes();
                if (attr != null) {
                    loggerLevels.put(attr.getNamedItem("name").getNodeValue(),
                            attr.getNamedItem("level").getNodeValue());
                }
            }
        } catch (ParserConfigurationException | IOException | SAXException ex) {
            LOG.error("Failed to parse logback configuration file {}", fileName, ex);
            Assertions.fail("Failed to parse logback configuration file " + fileName, ex);
        }
        return loggerLevels;
    }

    @Test
    void testPropertiesLoad() throws Exception {
        initBasePropertiesManager();
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Properties nifiRegistryProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiRegistryPropsConfig))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("200", nifiRegistryProps.getProperty("nifi.registry.web.jetty.threads"));
            Assertions.assertEquals("15",
                    nifiRegistryProps.getProperty("nifi.registry.db.maxConnections"));
            Assertions.assertEquals("10 secs",
                    nifiRegistryProps.getProperty("nifi.registry.security.user.oidc.connect.timeout"));
            Assertions.assertEquals("NONE",
                    nifiRegistryProps.getProperty("nifi.registry.security.identity.mapping.transform.dn"));
            Assertions.assertFalse(nifiRegistryProps.containsKey("test.non-matching.property"),
                    "test.non-matching.property property is not filtered by prefix");
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi-registry.properties", e);
        }
        Map<String, String> loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "INFO",
                        "org.qubership", "DEBUG"),
                loggerLevels);
        File customProperties = new File("./conf/custom.properties");
        Assertions.assertFalse(customProperties.exists(), "custom.properties should not exist");
    }

    @Test
    void testLogbackConfigUpdate() throws Exception {
        initBasePropertiesManager();
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Map<String, String> loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "INFO",
                        "org.qubership", "DEBUG"),
                loggerLevels);
        //add new LOG levels and run update:
        propertiesProvider.putProperty("logger.org.qubership.test1234", "debug");
        propertiesProvider.putProperty("logger.org.apache.nifi.registry.StdErr", "WARN");
        pm.updateLogbackConfig();
        Assertions.assertTrue(logbackConfig.exists());
        loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "WARN",
                        "org.qubership", "DEBUG",
                        "org.qubership.test1234", "debug"),
                loggerLevels);
    }

    @Test
    void testLogbackConfigUpdateWithLoggerRemoval() throws Exception {
        initBasePropertiesManager();
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Map<String, String> loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "INFO",
                        "org.qubership", "DEBUG"),
                loggerLevels);
        //add new LOG levels and run update:
        propertiesProvider.putProperty("logger.org.qubership.test1234", "debug");
        propertiesProvider.putProperty("logger.org.qubership.test5678", "info");
        propertiesProvider.putProperty("logger.org.apache.nifi.registry.StdErr", "WARN");
        pm.updateLogbackConfig();
        Assertions.assertTrue(logbackConfig.exists());
        loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "WARN",
                        "org.qubership", "DEBUG",
                        "org.qubership.test1234", "debug",
                        "org.qubership.test5678", "info"),
                loggerLevels);

        //set empty, null and remove property with default and without default:
        propertiesProvider.putProperty("logger.org.qubership.test1234", "");
        propertiesProvider.putProperty("logger.org.qubership.test5678", null);
        propertiesProvider.removeProperty("logger.org.apache.nifi.registry.StdErr");
        propertiesProvider.removeProperty("logger.org.qubership");
        //run update:
        pm.updateLogbackConfig();
        Assertions.assertTrue(logbackConfig.exists());
        loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "ERROR"),
                loggerLevels);
    }

    @Test
    void testPropertiesLoadWithNullPrefix() throws Exception {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", null, Set.of(""));
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Properties nifiRegistryProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiRegistryPropsConfig))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("200", nifiRegistryProps.getProperty("nifi.registry.web.jetty.threads"));
            Assertions.assertEquals("15",
                    nifiRegistryProps.getProperty("nifi.registry.db.maxConnections"));
            Assertions.assertEquals("10 secs",
                    nifiRegistryProps.getProperty("nifi.registry.security.user.oidc.connect.timeout"));
            Assertions.assertEquals("Some value",
                    nifiRegistryProps.getProperty("test.non-matching.property"));
            Assertions.assertEquals("NONE",
                    nifiRegistryProps.getProperty("nifi.registry.security.identity.mapping.transform.dn"));
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi-registry.properties", e);
        }
    }

    @Test
    void testPropertiesLoadWithEmptyPrefix() throws Exception {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "", Set.of(""));
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Properties nifiRegistryProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiRegistryPropsConfig))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("200", nifiRegistryProps.getProperty("nifi.registry.web.jetty.threads"));
            Assertions.assertEquals("15",
                    nifiRegistryProps.getProperty("nifi.registry.db.maxConnections"));
            Assertions.assertEquals("10 secs",
                    nifiRegistryProps.getProperty("nifi.registry.security.user.oidc.connect.timeout"));
            Assertions.assertEquals("Some value",
                    nifiRegistryProps.getProperty("test.non-matching.property"));
            Assertions.assertEquals("NONE",
                    nifiRegistryProps.getProperty("nifi.registry.security.identity.mapping.transform.dn"));
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi-registry.properties", e);
        }
    }

    @Test
    void testPropertiesLoadWithReadonlyProperties() throws Exception {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "",
                Set.of("nifi.registry.security.identity.mapping.transform.dn"));
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Properties nifiRegistryProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiRegistryPropsConfig))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("200", nifiRegistryProps.getProperty("nifi.registry.web.jetty.threads"));
            Assertions.assertEquals("15",
                    nifiRegistryProps.getProperty("nifi.registry.db.maxConnections"));
            Assertions.assertEquals("10 secs",
                    nifiRegistryProps.getProperty("nifi.registry.security.user.oidc.connect.timeout"));
            Assertions.assertEquals("Some value",
                    nifiRegistryProps.getProperty("test.non-matching.property"));
            Assertions.assertFalse(
                    nifiRegistryProps.containsKey("nifi.registry.security.identity.mapping.transform.dn"),
                    "Ignored property nifi.registry.security.identity.mapping.transform.dn must not be loaded");
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi-registry.properties", e);
        }
    }

    @Test
    void testNonExistingTemplate() {
        initBasePropertiesManager("logback-template-not-exists.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "",
                Set.of());
        IOException ex = Assertions.assertThrows(IOException.class, pm::generateNifiPropertiesAndLogbackConfig);
        LOG.info("Caught expected exception", ex);
        Assertions.assertEquals("Resource not found: logback-template-not-exists.xml", ex.getMessage());
    }

    @Test
    void testNonExistingDefaultPropertiesTemplate() {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default-not-exists.properties",
                "./conf/", "nifi-registry.properties", "",
                Set.of());
        IOException ex = Assertions.assertThrows(IOException.class, pm::generateNifiPropertiesAndLogbackConfig);
        LOG.info("Caught expected exception", ex);
        Assertions.assertEquals("Resource not found: nifi_registry_default-not-exists.properties", ex.getMessage());
    }

    @Test
    void testNonExistingTargetDirectory() {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default.properties",
                "./conf-not-exists/", "nifi-registry.properties", "",
                Set.of());
        IOException ex = Assertions.assertThrows(IOException.class, pm::generateNifiPropertiesAndLogbackConfig);
        LOG.info("Caught expected exception", ex);
        Assertions.assertNotNull(ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("conf-not-exists"),
                "Message should contain conf-not-exists");
        Assertions.assertTrue(ex.getMessage().contains("nifi-registry.properties"),
                "Message should contain nifi-registry.properties");
    }

    @Test
    void testInvalidLogbackTemplate() {
        initBasePropertiesManager("logback-template-invalid.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "",
                Set.of());
        SAXParseException ex = Assertions.assertThrows(SAXParseException.class,
                pm::generateNifiPropertiesAndLogbackConfig);
        LOG.info("Caught expected exception", ex);
        Assertions.assertNotNull(ex.getMessage());
    }

    @Test
    void testCustomPropertiesLoad() throws Exception {
        initBasePropertiesManager("logback-template.xml",
                "nifi_registry_default.properties",
                "./conf/", "nifi-registry.properties", "nifi.registry",
                Set.of(), Set.of("nifi.registry.custom.prop1", "nifi.registry.custom.prop2"));
        propertiesProvider.putProperty("nifi.registry.custom.prop1", "2");
        propertiesProvider.putProperty("nifi.registry.custom.prop2", "true");
        pm.generateNifiPropertiesAndLogbackConfig();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        File nifiRegistryPropsConfig = new File("./conf/nifi-registry.properties");
        Assertions.assertTrue(nifiRegistryPropsConfig.exists());
        Properties nifiRegistryProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiRegistryPropsConfig))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("200", nifiRegistryProps.getProperty("nifi.registry.web.jetty.threads"));
            Assertions.assertEquals("15",
                    nifiRegistryProps.getProperty("nifi.registry.db.maxConnections"));
            Assertions.assertEquals("10 secs",
                    nifiRegistryProps.getProperty("nifi.registry.security.user.oidc.connect.timeout"));
            Assertions.assertEquals("NONE",
                    nifiRegistryProps.getProperty("nifi.registry.security.identity.mapping.transform.dn"));
            Assertions.assertFalse(nifiRegistryProps.containsKey("test.non-matching.property"),
                    "test.non-matching.property property is not filtered by prefix");
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi-registry.properties", e);
        }
        Map<String, String> loggerLevels = parseLogbackConfig("./conf/logback.xml");
        Assertions.assertEquals(
                Map.of("org.apache.nifi.registry.StdErr", "INFO",
                        "org.qubership", "DEBUG"),
                loggerLevels);
        File customProperties = new File("./conf/custom.properties");
        Assertions.assertTrue(customProperties.exists(), "custom.properties should exist");
        try (InputStream in = new BufferedInputStream(new FileInputStream(customProperties))) {
            nifiRegistryProps.load(in);
            Assertions.assertEquals("2", nifiRegistryProps.getProperty("nifi.registry.custom.prop1"));
            Assertions.assertEquals("true",
                    nifiRegistryProps.getProperty("nifi.registry.custom.prop2"));
            Assertions.assertFalse(nifiRegistryProps.containsKey("nifi.registry.custom.prop3"),
                    "nifi.registry.custom.prop3 property is not filtered by set of names");
        } catch (IOException e) {
            Assertions.fail("Failed to read custom.properties", e);
        }
    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "nifi-registry.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
            Files.deleteIfExists(Paths.get(".", "conf", "custom.properties"));
            Files.deleteIfExists(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }
}
