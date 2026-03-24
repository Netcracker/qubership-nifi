package org.qubership.nifi.tools.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_OUTPUT_DIR = "./";

    private Main() { }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--sourceDir, --targetDir, --dictionaryPath, --outputPath);
     *             each flag must be followed by its value
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

        try {
            Path outputDir = resolveOutputDir(outputPath);

            JsonComparator comparator = new JsonComparator();
            comparator.load(sourceDir, targetDir, dictionaryPath);
            comparator.compare();

            CsvReportGenerator csvGenerator = new CsvReportGenerator(outputDir);
            csvGenerator.generate(comparator.getCsvRecords());

            JsonMappingGenerator jsonGenerator = new JsonMappingGenerator(outputDir);
            jsonGenerator.generate(
                    comparator.getTypeToChangedProperties(),
                    comparator.getTypeToFolderMap());

            LOGGER.info("Comparison completed successfully!");
            LOGGER.info("CSV Report:  {}", csvGenerator.getOutputPath());
            LOGGER.info("JSON Report: {}", jsonGenerator.getOutputPath());

        } catch (IOException e) {
            LOGGER.error("Fatal error during comparison: {}", e.getMessage(), e);
            System.exit(1);
        } catch (IllegalStateException e) {
            LOGGER.error("State error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static Path resolveOutputDir(String outputPath) throws IOException {
        Path dir = Paths.get(outputPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            LOGGER.info("Created output directory: {}", dir.toAbsolutePath());
        }
        return dir;
    }
}
