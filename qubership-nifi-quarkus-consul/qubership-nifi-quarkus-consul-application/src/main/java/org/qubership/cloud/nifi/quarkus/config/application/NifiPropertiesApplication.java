package org.qubership.cloud.nifi.quarkus.config.application;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.nifi.config.xml.BaseXmlConfigValidator;
import org.qubership.cloud.nifi.quarkus.config.PropertiesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main Quarkus application for NiFi Consul configuration management.
 * <p>
 * This application observes the StartupEvent and triggers the generation of
 * NiFi configuration files from Consul properties.
 */
@QuarkusMain
public class NifiPropertiesApplication implements QuarkusApplication {
    private static final Logger LOG = LoggerFactory.getLogger(NifiPropertiesApplication.class);

    @Inject
    private PropertiesManager propertiesManager;

    @Inject
    private BaseXmlConfigValidator baseXmlConfigValidator;

    @Inject
    @ConfigProperty(name = "nifi.notification.path")
    private String notificationPath;

    /**
     * Main application entrypoint.
     *
     * @param args command line arguments
     */
    public static void main(String... args) {
        Quarkus.run(NifiPropertiesApplication.class, args);
    }

    /**
     * Runs quarkus application.
     * @param args command line arguments
     * @return exit code
     * @throws Exception unexpected exception
     */
    @Override
    public int run(String... args) throws Exception {
        // Generate properties on startup
        generateProperties();

        // Keep the application running (exit after completion)
        Quarkus.waitForExit();
        return 0;
    }

    /**
     * Generates NiFi properties files and notifies startup script on completion.
     *
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws TransformerException
     * @throws SAXException
     */
    private void generateProperties() throws IOException, ParserConfigurationException, TransformerException,
            SAXException {
        LOG.info("Starting NiFi properties generation...");
        propertiesManager.generateNifiProperties();
        baseXmlConfigValidator.validate();
        notifyCompletionToStartScript();
        LOG.info("NiFi properties generation completed successfully");
    }

    private void notifyCompletionToStartScript() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Path fPath = Paths.get(notificationPath, "initial-config-completed.txt");
            Files.write(fPath, timestamp.getBytes());
            LOG.info("Consul App completion file created:{} ", fPath.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Error while creating completion file for consul app", e);
        }
    }

}
