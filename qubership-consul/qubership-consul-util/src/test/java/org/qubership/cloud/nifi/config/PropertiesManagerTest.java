package org.qubership.cloud.nifi.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Testcontainers
@SpringBootTest(classes = {PropertiesManager.class})
@ImportAutoConfiguration(RefreshAutoConfiguration.class)
public class PropertiesManagerTest {

    private static final String CONSUL_IMAGE = "hashicorp/consul:1.20";
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManagerTest.class);
    private static ConsulContainer consul;

    @Autowired
    private PropertiesManager pm;


    private static void putPropertyToConsul(String propertyName, String propertyValue) {
        Container.ExecResult res = null;
        try {
            res = consul.execInContainer("consul", "kv", "put", propertyName, propertyValue);
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
        List<String> consulPorts = new ArrayList<>();
        consulPorts.add("18500:8500");

        consul = new ConsulContainer(DockerImageName.parse(CONSUL_IMAGE));
        consul.setPortBindings(consulPorts);
        consul.start();

        //fill initial consul data:
        putPropertyToConsul("config/local/application/logger.org.qubership", "DEBUG");
        putPropertyToConsul("config/local/application/logger.org.apache.nifi.processors", "DEBUG");
        putPropertyToConsul("config/local/application/nifi.cluster.base-node-count", "5");
        putPropertyToConsul("config/local/application/nifi.nifi-registry.nar-provider-enabled", "true");
        putPropertyToConsul("config/local/application/nifi.queue.swap.threshold", "25000");
        putPropertyToConsul("config/local/application/nifi.web.https.application.protocols", "http/1.1 http/1.1");
        putPropertyToConsul("config/local/application/test.value", "true");

        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }


    @Test
    public void testPropertiesLoadOnStart() throws Exception {
        pm.generateNifiProperties();
        File logbackConfig = new File("./conf/logback.xml");
        Assertions.assertTrue(logbackConfig.exists());
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

    @AfterAll
    public static void tearDown() {
        consul.stop();
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
