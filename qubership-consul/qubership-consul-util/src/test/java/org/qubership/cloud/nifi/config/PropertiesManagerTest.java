package org.qubership.cloud.nifi.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.consul.config.ConsulConfigAutoConfiguration;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;

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

@SpringBootTest(classes = {PropertiesManager.class,
        ConsulConfiguration.class, ConsulPropertiesProvider.class})
@ImportAutoConfiguration(classes = {RefreshAutoConfiguration.class, ConsulConfigAutoConfiguration.class})
public class PropertiesManagerTest {

    private static final String CONSUL_IMAGE = "hashicorp/consul:1.20";
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManagerTest.class);
    private static final ConsulContainer CONSUL;

    static {
        CONSUL = new ConsulContainer(DockerImageName.parse(CONSUL_IMAGE));
        CONSUL.start();
        System.setProperty("consul.test.port", String.valueOf(CONSUL.getMappedPort(8500)));
    }

    @Autowired
    private PropertiesManager pm;


    private static void putPropertyToConsul(String propertyName, String propertyValue) {
        Container.ExecResult res = null;
        try {
            res = CONSUL.execInContainer("consul", "kv", "put", propertyName, propertyValue);
            LOG.debug("Result for put {} = {}",
                    propertyName, res.getStdout());
            Assertions.assertTrue(res.getStdout() != null && res.getStdout().contains("Success"),
                    "Failed to put property = " + propertyName
                            + ". Output: " + res.getStdout() + ". Error: " + res.getStderr());
        } catch (IOException | InterruptedException e) {
            if (res != null) {
                LOG.error("Last command stdout = {}", res.getStdout());
                LOG.error("Last command stderr = {}", res.getStderr());
            }
            LOG.error("Failed to fill initial consul data for property = {}", propertyName, e);
            Assertions.fail("Failed to fill initial consul data for property = " + propertyName, e);
        }
    }

    @BeforeAll
    public static void initContainer() {
        //fill initial consul data:
        putPropertyToConsul("config/local/application/logger.org.qubership", "DEBUG");
        putPropertyToConsul("config/local/application/logger.org.apache.nifi.processors", "DEBUG");
        putPropertyToConsul("config/local/application/nifi.cluster.base-node-count", "5");
        putPropertyToConsul("config/local/application/nifi.nifi-registry.nar-provider-enabled", "true");
        putPropertyToConsul("config/local/application/nifi.queue.swap.threshold", "25000");
        putPropertyToConsul("config/local/application/nifi.web.https.application.protocols", "http/1.1 http/1.1");
        putPropertyToConsul("config/local/application/test.value", "true");
    }

    @BeforeEach
    void prepareDirectories() {
        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }

    @Test
    void testPropertiesLoadOnStart() throws Exception {
        File logbackConfig = new File("./conf/logback.xml");
        //remove existing logback.xml:
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
        putPropertyToConsul("config/local/application/logger.org.qubership2", "DEBUG");
        //wait for logback.xml to be recreated after refresh:
        Awaitility.await().atMost(25000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        pm.generateNifiProperties();
        Assertions.assertTrue(logbackConfig.exists());
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership2"));
        Assertions.assertEquals("ERROR", loggingLevels.get("org.apache.nifi.StdErr"));
        Assertions.assertEquals("WARN", loggingLevels.get("org.apache.nifi.web.security"));
        File customPropsConfig = new File("./conf/custom.properties");
        Assertions.assertTrue(customPropsConfig.exists());
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists());
        Properties customProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(customPropsConfig))) {
            customProps.load(in);
            Assertions.assertEquals("5", customProps.getProperty("nifi.cluster.base-node-count"));
            Assertions.assertEquals("true", customProps.getProperty("nifi.nifi-registry.nar-provider-enabled"));
        } catch (IOException e) {
            Assertions.fail("Failed to read custom.properties", e);
        }
        Properties nifiProps = new Properties();
        try (InputStream in = new BufferedInputStream(new FileInputStream(nifiPropsConfig))) {
            nifiProps.load(in);
            Assertions.assertEquals("25000", nifiProps.getProperty("nifi.queue.swap.threshold"));
            Assertions.assertEquals("http/1.1 http/1.1",
                    nifiProps.getProperty("nifi.web.https.application.protocols"));
        } catch (IOException e) {
            Assertions.fail("Failed to read nifi.properties", e);
        }
    }

    @Test
    void testLoggingLevelsUpdate() throws Exception {
        //initial load:
        putPropertyToConsul("config/local/application/logger.org.qubership", "DEBUG");
        pm.generateNifiProperties();
        final File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
        LogbackConfigParser parser = new LogbackConfigParser("./conf/logback.xml");
        Map<String, String> loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"));
        Assertions.assertEquals("DEBUG", loggingLevels.get("org.qubership"));
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists());
        //remove existing logback.xml:
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
        //update consul:
        putPropertyToConsul("config/local/application/logger.org.qubership", "INFO");
        //wait for logback.xml to be recreated after refresh:
        Awaitility.await().atMost(25000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        Assertions.assertTrue(logbackConfig.exists());
        loggingLevels = parser.getAllLoggingLevels();
        Assertions.assertTrue(loggingLevels.containsKey("org.qubership"));
        Assertions.assertEquals("INFO", loggingLevels.get("org.qubership"));
    }

    @AfterEach
    void cleanUpDirectories() {
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "nifi.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "custom.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }

    @AfterAll
    public static void tearDown() {
        System.clearProperty("consul.test.port");
        CONSUL.stop();
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "custom.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "nifi.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
            Files.deleteIfExists(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }
}
