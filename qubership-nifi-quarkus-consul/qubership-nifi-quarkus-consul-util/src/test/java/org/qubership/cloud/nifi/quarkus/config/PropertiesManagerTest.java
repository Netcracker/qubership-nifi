package org.qubership.cloud.nifi.quarkus.config;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.nifi.config.LogbackConfigParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@QuarkusTest
@QuarkusTestResource(ConsulTestResource.class)
public class PropertiesManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManagerTest.class);

    /**
     * Properties manager instance.
     */
    @Inject
    private PropertiesManager pm;

    /**
     * Consul container instance.
     */
    @InjectConsulContainer
    private ConsulContainer consul;

    @BeforeAll
    static void setup() {
        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }

    private void putToConsul(String key, String value) {
        Container.ExecResult res = null;
        try {
            res = consul.execInContainer(
                    "consul", "kv", "put", key, value);
            LOG.debug("Result for put key = {}: {}", key, res.getStdout());
            Assertions.assertTrue(res.getStdout() != null && res.getStdout().contains("Success"));
        } catch (IOException | InterruptedException e) {
            if (res != null) {
                LOG.error("Put command stdout = {}, stderr = {}", res.getStdout(), res.getStderr());
            }
            LOG.error("Failed to put consul data", e);
            Assertions.fail("Failed to put consul data", e);
        }
    }

    private void deleteFromConsul(String key) {
        Container.ExecResult res = null;
        try {
            res = consul.execInContainer(
                    "consul", "kv", "delete", key);
            LOG.debug("Result for delete key = {}: {}", key, res.getStdout());
            Assertions.assertTrue(res.getStdout() != null && res.getStdout().contains("Success"));
        } catch (IOException | InterruptedException e) {
            if (res != null) {
                LOG.error("Delete command stdout = {}, stderr = {}", res.getStdout(), res.getStderr());
            }
            LOG.error("Failed to delete consul key", e);
            Assertions.fail("Failed to delete consul key", e);
        }
    }

    @Test
    void testPropertiesLoadOnStart() throws Exception {
        File logbackConfig = new File("./conf/logback.xml");
        putToConsul("config/local/application/logger.org.qubership", "DEBUG");
        pm.generateNifiProperties();
        Assertions.assertTrue(logbackConfig.exists(), "logback.xml should exist");
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"), "Should contain org.qubership logging level");
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"), "Should be DEBUG");
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists(), "nifi.properties should exist");
        Properties nifiProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiPropsConfig))) {
            nifiProps.load(in);
            Assertions.assertEquals("60 secs", nifiProps.getProperty("nifi.security.user.oidc.token.refresh.window"),
                    "Default property value should be loaded");
            Assertions.assertEquals("400",
                    nifiProps.getProperty("nifi.web.jetty.threads"),
                    "Consul property should override default");
            Assertions.assertEquals("10",
                    nifiProps.getProperty("nifi.cluster.flow.election.max.candidates"),
                    "Consul property without default should be loaded");
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi.properties", e);
        }
    }

    @Test
    void testUpdateLoggingLevels() throws Exception {
        File logbackConfig = new File("./conf/logback.xml");
        putToConsul("config/local/application/logger.org.qubership", "DEBUG");
        pm.generateNifiProperties();
        Assertions.assertTrue(logbackConfig.exists(), "logback.xml should exist");
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"), "Should contain org.qubership logging level");
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"), "Should be DEBUG");
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists(), "nifi.properties should exist");
        //update:
        //remove existing logback.xml:
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
        //update consul:
        putToConsul("config/local/application/logger.org.qubership", "INFO");
        //wait for logback.xml to be recreated after refresh:
        Awaitility.await().atMost(25000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        Assertions.assertTrue(logbackConfig.exists());
        loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"));
        Assertions.assertEquals("INFO", loggingLevels.get("org.qubership"));
    }

    @Test
    void testAddLoggingLevels() throws Exception {
        File logbackConfig = new File("./conf/logback.xml");
        putToConsul("config/local/application/logger.org.qubership", "DEBUG");
        pm.generateNifiProperties();
        Assertions.assertTrue(logbackConfig.exists(), "logback.xml should exist");
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"), "Should contain org.qubership logging level");
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"), "Should be DEBUG");
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists(), "nifi.properties should exist");
        //update:
        //remove existing logback.xml:
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
        //update consul:
        putToConsul("config/local/application/logger.org.qubership2", "INFO");
        //wait for logback.xml to be recreated after refresh:
        Awaitility.await().atMost(25000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        Assertions.assertTrue(logbackConfig.exists());
        loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"));
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"));
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership2"));
        Assertions.assertEquals("INFO", loggingLevels.get("org.qubership2"));
    }


    @Test
    void testRemoveLoggingLevels() throws Exception {
        File logbackConfig = new File("./conf/logback.xml");
        putToConsul("config/local/application/logger.org.qubership", "DEBUG");
        putToConsul("config/local/application/logger.org.qubership2", "WARN");
        putToConsul("config/local/application/logger.org.qubership3", "ERROR");
        pm.generateNifiProperties();
        Assertions.assertTrue(logbackConfig.exists(), "logback.xml should exist");
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"), "Should contain org.qubership logging level");
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"), "Should be DEBUG");
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership2"),
                "Should contain org.qubership2 logging level");
        Assertions.assertEquals("WARN", loggingLevels.get("org.qubership2"), "Should be WARN");
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership3"),
                "Should contain org.qubership3 logging level");
        Assertions.assertEquals("ERROR", loggingLevels.get("org.qubership3"), "Should be ERROR");
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists(), "nifi.properties should exist");
        //update:
        //remove existing logback.xml:
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
        //update consul:
        deleteFromConsul("config/local/application/logger.org.qubership2");
        deleteFromConsul("config/local/application/logger.org.qubership3");
        //wait for logback.xml to be recreated after refresh:
        Awaitility.await().atMost(25000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        Assertions.assertTrue(logbackConfig.exists());
        loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"));
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"));
        Assertions.assertFalse(loggingLevels.containsKey("org.qubership2"));
        Assertions.assertNull(loggingLevels.get("org.qubership2"));
        Assertions.assertFalse(loggingLevels.containsKey("org.qubership3"));
        Assertions.assertNull(loggingLevels.get("org.qubership3"));
    }

    @AfterAll
    static void tearDown() {
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "nifi.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
            Files.deleteIfExists(Paths.get(".", "conf", "custom.properties"));
            Files.deleteIfExists(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }
}
