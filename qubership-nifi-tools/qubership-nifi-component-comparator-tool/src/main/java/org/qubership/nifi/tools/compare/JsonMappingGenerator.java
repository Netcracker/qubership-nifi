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

public class JsonMappingGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonMappingGenerator.class);

    private static final String JSON_OUTPUT_FILE = "NiFiTypeMapping.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> INCLUDED_FOLDERS = Set.of(
            "controllerService", "reportingTask"
    );

    private final Path outputDir;

    /**
     * Creates a new JSON mapping generator.
     *
     * @param outputDirValue directory where the JSON file will be written
     */
    public JsonMappingGenerator(final Path outputDirValue) {
        this.outputDir = outputDirValue;
    }

    /**
     * Generates the type-mapping JSON file.
     * Components whose subfolder is not in {@link #INCLUDED_FOLDERS}
     * (e.g. processors) are excluded from the output.
     * <p>
     * Renamed properties are written as {@code "oldName": "newName"}.
     * Deleted properties are written as {@code "apiName": null}.
     *
     * @param typeToChangedProperties map of componentType to (name → newName or null) changes
     * @param typeToFolderMap         map of componentType to subfolder name
     */
    public void generate(Map<String, Map<String, String>> typeToChangedProperties,
                         Map<String, String> typeToFolderMap) {
        LOGGER.info("Generating type mapping JSON...");

        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();
        typeToChangedProperties.forEach((type, changes) -> {
            String folder = typeToFolderMap.get(type);
            if (folder != null && !INCLUDED_FOLDERS.contains(folder)) {
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
     * @return absolute path to NiFiTypeMapping.json
     */
    public String getOutputPath() {
        return outputDir.resolve(JSON_OUTPUT_FILE).toAbsolutePath().toString();
    }
}
