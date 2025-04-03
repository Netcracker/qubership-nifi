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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Testcontainers
@SpringBootTest(classes = {PropertiesManager.class})
@ImportAutoConfiguration(RefreshAutoConfiguration.class)
public class PropertiesManagerTest {

    private static final String CONSUL_IMAGE = "hashicorp/consul:1.20";
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManagerTest.class);
    private static ConsulContainer consul;

    @Autowired
    private PropertiesManager pm;

    @BeforeAll
    public static void initContainer() {
        List<String> consulPorts = new ArrayList<>();
        consulPorts.add("18500:8500");

        consul = new ConsulContainer(DockerImageName.parse(CONSUL_IMAGE));
        consul.setPortBindings(consulPorts);
        consul.start();

        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }

    @Test
    public void testPropertiesLoadOnStart() throws Exception {
        Container.ExecResult res = consul.execInContainer(
                "consul", "kv", "put", "config/local/application/logger.org.qubership", "DEBUG");
        LOG.debug("Result for put config/local/application/logger.org.qubership = {}", res.getStdout());
        Assertions.assertTrue(res.getStdout() != null && res.getStdout().contains("Success"));
        pm.generateNifiProperties();
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
