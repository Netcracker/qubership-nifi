package org.qubership.nifi.tools.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Main entrypoint for component comparator tool.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_OUTPUT_DIR = "./";

    private Main() { }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--sourceDir, --targetDir, --dictionaryPath, --outputPath,
     *             --version); each flag must be followed by its value. --version is optional; when
     *             given, its major.minor part (dot-separated) is appended to output file names
     */
    public static void main(String[] args) {
        String sourceDir = "";
        String targetDir = "";
        String dictionaryPath = "";
        String outputPath = DEFAULT_OUTPUT_DIR;
        String version = null;

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
                case "--version":
                    version = args[++i];
                    break;
                default:
                    // ignore unknown flags
                    break;
            }
        }

        String versionSuffix = buildVersionSuffix(version);

        LOGGER.info("Starting NiFi Component Comparison...");
        LOGGER.info("Source Directory: {}", sourceDir);
        LOGGER.info("Target Directory: {}", targetDir);
        LOGGER.info("Dictionary File:  {}", (dictionaryPath != null) ? dictionaryPath : "None");
        LOGGER.info("Output Path: {}", outputPath);
        LOGGER.info("Version: {}", (version != null) ? version : "None");

        try {
            Path outputDir = resolveOutputDir(outputPath);

            JsonComparator comparator = new JsonComparator();
            comparator.load(sourceDir, targetDir, dictionaryPath);
            comparator.compare();

            CsvReportGenerator csvGenerator = new CsvReportGenerator(outputDir);
            csvGenerator.generate(comparator.getCsvRecords());

            JsonMappingGenerator jsonGenerator = new JsonMappingGenerator(
                    outputDir, "csPropConfig" + versionSuffix + ".json",
                    Set.of("controllerService", "reportingTask"));
            jsonGenerator.generate(
                    comparator.getTypeToChangedProperties(),
                    comparator.getTypeToFolderMap());

            JsonMappingGenerator processorJsonGenerator = new JsonMappingGenerator(
                    outputDir, "procPropConfig" + versionSuffix + ".json", Set.of("processors"));
            processorJsonGenerator.generate(
                    comparator.getTypeToChangedProperties(),
                    comparator.getTypeToFolderMap());

            JsonMappingGenerator removeWhenEmptyJsonGenerator = new JsonMappingGenerator(
                    outputDir, "csRemoveWhenEmptyConfig" + versionSuffix + ".json",
                    Set.of("controllerService", "reportingTask"));
            removeWhenEmptyJsonGenerator.generate(
                    comparator.getTypeToDescriptorsToRemoveWhenEmpty(),
                    comparator.getTypeToFolderMap());

            JsonMappingGenerator processorRemoveWhenEmptyJsonGenerator = new JsonMappingGenerator(
                    outputDir, "procRemoveWhenEmptyConfig" + versionSuffix + ".json", Set.of("processors"));
            processorRemoveWhenEmptyJsonGenerator.generate(
                    comparator.getTypeToDescriptorsToRemoveWhenEmpty(),
                    comparator.getTypeToFolderMap());

            MarkdownReportGenerator mdGenerator = new MarkdownReportGenerator(outputDir);
            mdGenerator.generate(comparator.getCsvRecords());

            LOGGER.info("Comparison completed successfully!");
            LOGGER.info("CSV Report:           {}", csvGenerator.getOutputPath());
            LOGGER.info("JSON Report:          {}", jsonGenerator.getOutputPath());
            LOGGER.info("Processor JSON Report: {}", processorJsonGenerator.getOutputPath());
            LOGGER.info("Remove-when-empty JSON Report:           {}",
                    removeWhenEmptyJsonGenerator.getOutputPath());
            LOGGER.info("Processor remove-when-empty JSON Report: {}",
                    processorRemoveWhenEmptyJsonGenerator.getOutputPath());
            LOGGER.info("Markdown Report:      {}", mdGenerator.getOutputPath());

        } catch (IOException e) {
            LOGGER.error("Fatal error during comparison: {}", e.getMessage(), e);
            System.exit(1);
        } catch (IllegalStateException e) {
            LOGGER.error("State error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Builds a file-name suffix from a dot-separated version string, using only
     * the major and minor components (patch, if present, is dropped).
     *
     * @param version raw version string, e.g. "2.10.0"; may be null or blank
     * @return suffix such as "_2_10", or an empty string when version is not provided
     */
    private static String buildVersionSuffix(String version) {
        if (version == null || version.isBlank()) {
            return "";
        }
        String[] parts = version.split("\\.");
        int count = Math.min(parts.length, 2);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append('_').append(parts[i]);
        }
        return sb.toString();
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
