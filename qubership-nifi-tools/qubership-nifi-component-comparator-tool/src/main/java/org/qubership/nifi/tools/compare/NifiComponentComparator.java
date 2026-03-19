package org.qubership.nifi.tools.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class NifiComponentComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NifiComponentComparator.class);

    private static final String DEFAULT_OUTPUT_DIR = "./";

    private NifiComponentComparator() { }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--sourceDir, --targetDir, --dictionaryPath, --outputPath);
     *             each flag must be followed by its value — a flag provided as the last
     *             argument without a value causes an {@link ArrayIndexOutOfBoundsException}
     * @throws Exception if any step of the compare process fails
     */
    public static void main(String[] args) {
        String sourceDir = "";
        String targetDir = "";
        String dictionaryPath = "";
        String outputPath = DEFAULT_OUTPUT_DIR;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sourceDir":
                    sourceDir = args[++i];
                    break;
                case "--targetDir":
                    targetDir = args[++i];
                    break;
                case "--dictionaryPath":
                    dictionaryPath = args[++i];
                    break;
                case "--outputPath":
                    outputPath = args[++i];
                    break;
                default:
                    // ignore unknown flags
                    break;
            }
        }

        LOGGER.info("Starting NiFi Component Comparison...");
        LOGGER.info("Source Directory: {}", sourceDir);
        LOGGER.info("Target Directory: {}", targetDir);
        LOGGER.info("Dictionary File:  {}", (dictionaryPath != null) ? dictionaryPath : "None");
        LOGGER.info("Output Path: {}", outputPath);

        JsonComparator comparator = new JsonComparator();

        try {

            comparator.setOutputDir(outputPath);

            comparator.load(sourceDir, targetDir, dictionaryPath);

            comparator.compare();
            comparator.writeCsvReport();
            comparator.generateTypeMappingJson();

            LOGGER.info("Comparison completed successfully!");
            LOGGER.info("CSV Report:  {}", comparator.getCsvOutputPath());
            LOGGER.info("JSON Report: {}", comparator.getJsonOutputPath());

        } catch (IOException e) {
            LOGGER.error("Fatal error during comparison: {}", e.getMessage(), e);
            System.exit(1);
        } catch (IllegalStateException e) {
            LOGGER.error("State error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
