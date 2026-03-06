package org.qubership.nifi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class NifiComponentComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NifiComponentComparator.class);

    /**
     * Comparison of components from two NiFi systems and report generation.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            LOGGER.error("Not enough command line arguments.");
            System.exit(1);
        }

        String sourceDir = args[0];
        String targetDir = args[1];
        String dictionaryPath = (args.length > 2) ? args[2] : null;

        if (dictionaryPath != null && dictionaryPath.trim().isEmpty()) {
            dictionaryPath = null;
        }

        LOGGER.info("Starting NiFi Component Comparison...");
        LOGGER.info("Source Directory: {}", sourceDir);
        LOGGER.info("Target Directory: {}", targetDir);
        LOGGER.info("Dictionary File:  {}", (dictionaryPath != null) ? dictionaryPath : "None");

        JsonComparator comparator = new JsonComparator();

        try {
            if (dictionaryPath != null) {
                comparator.load(sourceDir, targetDir, dictionaryPath);
            } else {
                comparator.load(sourceDir, targetDir);
            }
            comparator.compare();
            comparator.generateTypeMappingJson();

            LOGGER.info("========================================");
            LOGGER.info("Comparison completed successfully!");
            LOGGER.info("CSV Report:  {}", comparator.getCsvOutputPath());
            LOGGER.info("JSON Report: {}", comparator.getJsonOutputPath());
            LOGGER.info("========================================");

        } catch (IOException e) {
            LOGGER.error("Fatal error during comparison: {}", e.getMessage(), e);
            System.exit(1);
        } catch (IllegalStateException e) {
            LOGGER.error("State error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
