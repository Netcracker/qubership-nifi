package org.qubership.cloud.nifi.quarkus.config.application;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.nifi.quarkus.config.ConsulTestResource;
import org.qubership.cloud.nifi.quarkus.config.InjectConsulContainer;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@QuarkusMainTest
@QuarkusTestResource(ConsulTestResource.class)
public class NifiPropertiesApplicationTest {
    /**
     * Consul container instance.
     * Reserved for future development.
     */
    @InjectConsulContainer
    private ConsulContainer consul;

    @BeforeAll
    static void setup() {
        //prepare test directories:
        try {
            Files.createDirectories(Paths.get(".", "conf"));
            Files.createDirectories(Paths.get(".", "tmp"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test dir", e);
        }
    }

    private Thread mainThread;

    @Test
    void testPropertiesLoadOnStart(QuarkusMainLauncher launcher) {
        mainThread = new Thread(launcher::launch);
        mainThread.start();
        //wait for logback.xml file creation:
        File logbackConfig = new File("./conf/logback.xml");
        Awaitility.await().atMost(30000, TimeUnit.MILLISECONDS).
                until(logbackConfig::exists);
        Assertions.assertTrue(logbackConfig.exists(), "logback.xml should exist");
        File nifiPropsConfig = new File("./conf/nifi.properties");
        Assertions.assertTrue(nifiPropsConfig.exists(), "nifi.properties should exist");
        Assertions.assertTrue(Files.exists(Paths.get(".", "tmp", "initial-config-completed.txt")));
    }

    @AfterEach
    void cleanupThread() {
        if (mainThread != null) {
            mainThread.interrupt();
        }
    }

    @AfterAll
    static void tearDown() {
        try {
            Files.deleteIfExists(Paths.get(".", "conf", "nifi.properties"));
            Files.deleteIfExists(Paths.get(".", "conf", "logback.xml"));
            Files.deleteIfExists(Paths.get(".", "conf", "custom.properties"));
            Files.deleteIfExists(Paths.get(".", "conf"));
            Files.deleteIfExists(Paths.get(".", "tmp", "initial-config-completed.txt"));
            Files.deleteIfExists(Paths.get(".", "tmp"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conf test dir", e);
        }
    }
}
