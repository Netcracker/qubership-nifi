package org.qubership.nifi.tools.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Generates JSON mapping for update scripts.
 */
public class JsonMappingGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonMappingGenerator.class);

    private static final String DEFAULT_OUTPUT_FILE = "csPropConfig.json";

    private static final Set<String> DEFAULT_INCLUDED_FOLDERS = Set.of(
            "controllerService", "reportingTask"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path outputDir;

    private final String outputFileName;

    private final Set<String> includedFolders;

    /**
     * Creates a JSON mapping generator for controller-service and reporting-task types.
     * Writes to {@code NiFiTypeMapping.json}.
     *
     * @param outputDirValue directory where the JSON file will be written
     */
    public JsonMappingGenerator(final Path outputDirValue) {
        this(outputDirValue, DEFAULT_OUTPUT_FILE, DEFAULT_INCLUDED_FOLDERS);
    }

    /**
     * Creates a JSON mapping generator for a specific output file and set of subfolders.
     *
     * @param outputDirValue      directory where the JSON file will be written
     * @param outputFileNameValue name of the JSON file to write
     * @param includedFoldersValue subfolders whose types are included in the output
     */
    public JsonMappingGenerator(final Path outputDirValue,
                                final String outputFileNameValue,
                                final Set<String> includedFoldersValue) {
        this.outputDir = outputDirValue;
        this.outputFileName = outputFileNameValue;
        this.includedFolders = includedFoldersValue;
    }

    /**
     * Generates the type-mapping JSON file.
     * Components whose subfolder is not in the configured included folders are excluded
     * from the output.
     * <p>
     * Renamed properties are written as {@code "oldName": "newName"}.
     * Deleted properties are written as {@code "apiName": null}.
     *
     * @param typeToChangedProperties map of componentType to (name -> newName or null) changes
     * @param typeToFolderMap         map of componentType to subfolder name
     */
    public void generate(Map<String, Map<String, String>> typeToChangedProperties,
                         Map<String, String> typeToFolderMap) {
        LOGGER.info("Generating type mapping JSON for {}...", outputFileName);

        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();
        typeToChangedProperties.forEach((type, changes) -> {
            String folder = typeToFolderMap.get(type);
            if (folder != null && !includedFolders.contains(folder)) {
                LOGGER.debug("Skipping type {} from folder '{}' in JSON mapping", type, folder);
                return;
            }
            ObjectNode typeNode = OBJECT_MAPPER.createObjectNode();
            changes.forEach((key, value) -> {
                if (value != null) {
                    typeNode.put(key, value);
                } else {
                    typeNode.putNull(key);
                }
            });
            rootNode.set(type, typeNode);
        });

        if (rootNode.isEmpty()) {
            LOGGER.info("No changes found, skipping JSON mapping file generation: {}", getOutputPath());
            return;
        }

        try (FileWriter writer = new FileWriter(getOutputPath())) {
            writer.write(OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(rootNode));
            LOGGER.info("Type mapping JSON written to: {}", getOutputPath());
            LOGGER.info("Total types with changes: {}", rootNode.size());
        } catch (IOException e) {
            LOGGER.error("Error writing JSON file: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the absolute path of the JSON output file.
     *
     * @return absolute path to the configured JSON output file
     */
    public String getOutputPath() {
        return outputDir.resolve(outputFileName).toAbsolutePath().toString();
    }
}
