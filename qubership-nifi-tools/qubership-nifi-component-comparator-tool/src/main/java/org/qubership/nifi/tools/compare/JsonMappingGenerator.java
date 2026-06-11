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

    private static final String JSON_OUTPUT_FILE = "NiFiTypeMapping.json";

    private static final String CONTROLLER_SERVICE_REFERENCES_KEY = "controllerServiceReferences";

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
     * <p>
     * Renamed properties that reference a controller service are additionally written
     * under a sibling {@code controllerServiceReferences} object as
     * {@code "componentType": {"newApiName": "controllerServiceType"}}. The section is
     * omitted when there are no such references.
     *
     * @param typeToChangedProperties      map of componentType to (name -> newName or null) changes
     * @param typeToFolderMap              map of componentType to subfolder name
     * @param typeToControllerServiceRefs  map of componentType to (new API name ->
     *                                     controller-service type) for CS-reference properties
     */
    public void generate(Map<String, Map<String, String>> typeToChangedProperties,
                         Map<String, String> typeToFolderMap,
                         Map<String, Map<String, String>> typeToControllerServiceRefs) {
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

        addControllerServiceReferences(rootNode, typeToControllerServiceRefs, typeToFolderMap);

        try (FileWriter writer = new FileWriter(getOutputPath())) {
            writer.write(OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(rootNode));
            LOGGER.info("Type mapping JSON written to: {}", getOutputPath());
            LOGGER.info("Total types with changes: {}", rootNode.size());
        } catch (IOException e) {
            LOGGER.error("Error writing JSON file: {}", e.getMessage(), e);
        }
    }

    private void addControllerServiceReferences(ObjectNode rootNode,
                                                Map<String, Map<String, String>> typeToControllerServiceRefs,
                                                Map<String, String> typeToFolderMap) {
        if (typeToControllerServiceRefs == null || typeToControllerServiceRefs.isEmpty()) {
            return;
        }

        ObjectNode referencesNode = OBJECT_MAPPER.createObjectNode();
        typeToControllerServiceRefs.forEach((type, refs) -> {
            String folder = typeToFolderMap.get(type);
            if (folder != null && !INCLUDED_FOLDERS.contains(folder)) {
                return;
            }
            if (refs == null || refs.isEmpty()) {
                return;
            }
            ObjectNode typeNode = OBJECT_MAPPER.createObjectNode();
            refs.forEach(typeNode::put);
            referencesNode.set(type, typeNode);
        });

        if (!referencesNode.isEmpty()) {
            rootNode.set(CONTROLLER_SERVICE_REFERENCES_KEY, referencesNode);
            LOGGER.info("Controller-service references written for {} types", referencesNode.size());
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
