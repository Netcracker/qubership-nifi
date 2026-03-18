package org.qubership.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares NiFi component JSON descriptors between two directory trees,
 * producing a CSV delta report and a JSON type-mapping file.
 */
public class JsonComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonComparator.class);

    private static final List<String> TARGET_SUBFOLDERS = List.of(
            "controllerService",
            "processors",
            "reportingTask"
    );

    private static final String CSV_OUTPUT_FILE  = "NiFiComponentsDelta.csv";
    private static final String JSON_OUTPUT_FILE = "NiFiTypeMapping.json";

    private static final String[] CSV_HEADERS = {
            "Component Name", "Component Type", "Change Type",
            "Old Display Name", "New Display Name",
            "Old Api Name", "New Api Name"
    };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Map<String, String> sourceFilesPathMap = new HashMap<>();
    private Map<String, String> targetFilesPathMap = new HashMap<>();
    private Map<String, JsonNode> sourceJsonMap = new HashMap<>();
    private Map<String, JsonNode> targetJsonMap = new HashMap<>();
    private Map<String, String> fileTypeMap = new HashMap<>();
    private Map<String, String> fileSubfolderMap = new HashMap<>();
    private Map<String, Map<String, String>> dictionaryMappings = new HashMap<>();
    private final List<String[]> csvRecords = new ArrayList<>();
    private final Map<String, Map<String, String>> typeToRenamedProperties = new HashMap<>();

    private boolean isLoaded = false;
    private Path outputDir = Paths.get("./");

    private static final int NUMBER_OF_CHARACTERS = 5;

    /**
     * Loads source/target JSON trees and an optional rename dictionary.
     *
     * @param sourceRootPath   root directory of the "before" version
     * @param targetRootPath   root directory of the "after" version
     * @param dictionaryPath   path to a YAML mapping-dictionary (may be null/empty)
     */
    public void load(String sourceRootPath, String targetRootPath, String dictionaryPath) throws IOException {
        LOGGER.info("Starting data load...");
        clearState();

        sourceFilesPathMap.putAll(scanDirectory(sourceRootPath));
        targetFilesPathMap.putAll(scanDirectory(targetRootPath));

        sourceJsonMap.putAll(loadJsonContent(sourceFilesPathMap));
        targetJsonMap.putAll(loadJsonContent(targetFilesPathMap));

        if (dictionaryPath != null && !dictionaryPath.isEmpty()) {
            loadDictionaryMappings(dictionaryPath);
            LOGGER.info("Dictionary mappings loaded: {} types", dictionaryMappings.size());
        }

        isLoaded = true;
        LOGGER.info("Load completed. Source files: {}, Target files: {}",
                sourceJsonMap.size(), targetJsonMap.size());
    }

    /**
     * Loads source/target JSON trees without a mapping dictionary.
     * Convenience overload of {@link #load(String, String, String)}.
     *
     * @param sourceRootPath root directory of the "before" version
     * @param targetRootPath root directory of the "after" version
     * @throws IOException if any directory or file cannot be read
     */
    public void load(String sourceRootPath, String targetRootPath) throws IOException {
        load(sourceRootPath, targetRootPath, null);
    }

    /**
     * Resets all internal state maps and lists to prepare for a fresh load.
     */
    private void clearState() {
        sourceFilesPathMap.clear();
        targetFilesPathMap.clear();
        sourceJsonMap.clear();
        targetJsonMap.clear();
        fileTypeMap.clear();
        fileSubfolderMap.clear();
        dictionaryMappings.clear();
        csvRecords.clear();
        typeToRenamedProperties.clear();
    }

    private void loadDictionaryMappings(String dictionaryPath) throws IOException {
        Path dictPath = Paths.get(dictionaryPath);
        validateDictionaryPath(dictPath);

        LOGGER.info("Loading dictionary from: {}", dictPath.getFileName());

        try (FileInputStream fis = new FileInputStream(dictPath.toFile())) {
            Map<String, Object> data = new Yaml().loadAs(fis, Map.class);
            if (data == null || !data.containsKey("displayNameMapping")) {
                LOGGER.warn("No 'displayNameMapping' found in dictionary file");
                return;
            }
            parseMappingList(data.get("displayNameMapping"));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Error reading dictionary file: " + e.getMessage(), e);
        }

        LOGGER.info("Total component types loaded: {}", dictionaryMappings.size());
    }

    private void validateDictionaryPath(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Dictionary file does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Dictionary path must be a file, not a directory: " + path);
        }
        String name = path.getFileName().toString().toLowerCase();
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
            throw new IOException("Dictionary file must have .yaml or .yml extension: " + path);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseMappingList(Object mappingObj) {
        if (!(mappingObj instanceof List)) {
            return;
        }

        for (Object item : (List<?>) mappingObj) {
            if (!(item instanceof Map)) {
                continue;
            }

            ((Map<?, ?>) item).forEach((key, value) -> {
                if (!(value instanceof Map)) {
                    return;
                }
                Map<String, String> typeMappings = new HashMap<>();
                ((Map<?, ?>) value).forEach((oldName, newName) ->
                        typeMappings.put(oldName.toString().toLowerCase(), newName.toString())
                );
                if (!typeMappings.isEmpty()) {
                    dictionaryMappings.put(key.toString(), typeMappings);
                    LOGGER.info("Loaded: {} ({} mappings)", key, typeMappings.size());
                }
            });
        }
    }

    /**
     * Runs the comparison and writes the CSV report.
     * Must be called after {@link #load}.
     */
    public void compare() {
        requireLoaded();
        LOGGER.info("START COMPARISON: ");

        Set<String> sourceNames = sourceJsonMap.keySet();
        Set<String> targetNames = targetJsonMap.keySet();

        Set<String> onlyInSource = getDifference(sourceNames, targetNames);
        if (!onlyInSource.isEmpty()) {
            LOGGER.info("Files deleted completely: {}", onlyInSource.size());
        }

        Set<String> onlyInTarget = getDifference(targetNames, sourceNames);
        if (!onlyInTarget.isEmpty()) {
            LOGGER.info("Files added completely: {}", onlyInTarget.size());
        }

        Set<String> commonFiles = getIntersection(sourceNames, targetNames);
        if (!commonFiles.isEmpty()) {
            LOGGER.info("Comparing content of common files ({} items)...", commonFiles.size());
            compareCommonFiles(commonFiles);
        }

        writeCsvReport();
    }

    private void compareCommonFiles(Set<String> commonFiles) {
        for (String fileName : commonFiles) {
            JsonNode sourceProps   = sourceJsonMap.get(fileName);
            JsonNode targetProps   = targetJsonMap.get(fileName);
            String componentType   = fileTypeMap.get(fileName);
            String componentFolder = fileSubfolderMap.get(fileName);

            Map<String, String> nameMappings = resolveMappings(componentType);

            List<ComponentProperties> sourceList = buildComponentProperties(sourceProps, nameMappings);
            List<ComponentProperties> targetList = buildComponentProperties(targetProps, nameMappings);

            boolean sourceHasDuplicates = hasDisplayNameDuplicates(sourceList);
            boolean targetHasDuplicates = hasDisplayNameDuplicates(targetList);
            boolean useNonUnique = sourceHasDuplicates || targetHasDuplicates;

            processSourceProperties(fileName, componentType, componentFolder,
                    sourceList, targetList, useNonUnique);
            processAddedTargetProperties(fileName, componentType, componentFolder,
                    sourceList, targetList, useNonUnique);
        }
        LOGGER.info("Found property differences: {}", csvRecords.size());
    }

    private Map<String, String> resolveMappings(String componentType) {
        String shortType = getShortTypeName(componentType);
        if (shortType == null) {
            return Collections.emptyMap();
        }
        Map<String, String> mappings = dictionaryMappings.get(shortType);
        return mappings != null ? mappings : Collections.emptyMap();
    }

    private List<ComponentProperties> buildComponentProperties(JsonNode propsNode,
                                                               Map<String, String> nameMappings) {
        List<ComponentProperties> result = new ArrayList<>();
        if (propsNode == null) {
            return result;
        }

        propsNode.fields().forEachRemaining(entry -> {
            JsonNode prop       = entry.getValue();
            String apiName      = getNodeText(prop, "name");
            String displayName  = getNodeText(prop, "displayName");
            String description  = getNodeText(prop, "description");

            ComponentProperties cp = new ComponentProperties(apiName, displayName, description);
            cp.setEquivalentNameMappings(nameMappings);
            result.add(cp);
        });

        return result;
    }

    private boolean hasDisplayNameDuplicates(List<ComponentProperties> properties) {
        Set<String> seen = new HashSet<>();
        for (ComponentProperties cp : properties) {
            String dn = cp.getDisplayName();
            if (dn != null && !seen.add(dn.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void processSourceProperties(String fileName, String componentType,
                                         String componentFolder,
                                         List<ComponentProperties> sourceList,
                                         List<ComponentProperties> targetList,
                                         boolean useNonUnique) {
        for (ComponentProperties sourceProp : sourceList) {
            ComponentProperties matchingTarget = findMatchingTarget(sourceProp, targetList, useNonUnique);

            if (matchingTarget != null) {
                if (!Objects.equals(sourceProp.getApiName(), matchingTarget.getApiName())) {
                    csvRecords.add(createCsvRecord(fileName, componentFolder, "rename",
                            sourceProp.getDisplayName(), matchingTarget.getDisplayName(),
                            sourceProp.getApiName(), matchingTarget.getApiName()));
                    recordRename(componentType, sourceProp.getApiName(), matchingTarget.getApiName());
                }
            } else {
                csvRecords.add(createCsvRecord(fileName, componentFolder, "deleted",
                        sourceProp.getDisplayName(), "",
                        sourceProp.getApiName(), ""));
            }
        }
    }

    private void processAddedTargetProperties(String fileName, String componentType,
                                              String componentFolder,
                                              List<ComponentProperties> sourceList,
                                              List<ComponentProperties> targetList,
                                              boolean useNonUnique) {
        for (ComponentProperties targetProp : targetList) {
            boolean existsInSource = sourceList.stream()
                    .anyMatch(srcProp -> useNonUnique
                            ? srcProp.compareNonUniqueDisplayName(targetProp)
                            : srcProp.compareUniqueDisplayName(targetProp));

            if (!existsInSource) {
                csvRecords.add(createCsvRecord(fileName, componentFolder, "added",
                        "", targetProp.getDisplayName(),
                        "", targetProp.getApiName()));
            }
        }
    }

    private ComponentProperties findMatchingTarget(ComponentProperties sourceProp,
                                                   List<ComponentProperties> targetList,
                                                   boolean useNonUnique) {
        for (ComponentProperties targetProp : targetList) {
            boolean matches = useNonUnique
                    ? sourceProp.compareNonUniqueDisplayName(targetProp)
                    : sourceProp.compareUniqueDisplayName(targetProp);
            if (matches) {
                return targetProp;
            }
        }
        return null;
    }

    private void recordRename(String componentType, String oldApiName, String newApiName) {
        typeToRenamedProperties.computeIfAbsent(componentType, k -> new HashMap<>())
                .put(oldApiName, newApiName);
    }

    private String getShortTypeName(String fullTypeName) {
        if (fullTypeName == null || fullTypeName.isEmpty()) {
            return null;
        }
        int lastDot = fullTypeName.lastIndexOf('.');
        return (lastDot > 0 && lastDot < fullTypeName.length() - 1)
                ? fullTypeName.substring(lastDot + 1)
                : fullTypeName;
    }

    /**
     * Generates NiFiTypeMapping.json summarising all renamed properties grouped by
     * component type. Must be called after compare() so that rename data is available.
     *
     * @throws IllegalStateException if load() has not been called yet
     */
    public void generateTypeMappingJson() {
        requireLoaded();
        LOGGER.info("Generating type mapping JSON...");

        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();
        typeToRenamedProperties.forEach((type, renames) -> {
            ObjectNode typeNode = OBJECT_MAPPER.createObjectNode();
            renames.forEach(typeNode::put);
            rootNode.set(type, typeNode);
        });

        try (FileWriter writer = new FileWriter(getJsonOutputPath())) {
            writer.write(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode));
            LOGGER.info("Type mapping JSON written to: {}", getJsonOutputPath());
            LOGGER.info("Total types with renames: {}", typeToRenamedProperties.size());
        } catch (IOException e) {
            LOGGER.error("Error writing JSON file: {}", e.getMessage(), e);
        }
    }

    private String[] createCsvRecord(String filename, String componentType, String changeType,
                                     String displayNameOld, String displayNameNew,
                                     String apiNameOld, String apiNameNew) {
        return new String[]{
                removeJsonExtension(filename),
                componentType != null ? componentType : "",
                changeType,
                displayNameOld,
                displayNameNew,
                apiNameOld,
                apiNameNew
        };
    }

    public void writeCsvReport() {
        CSVFormat csvFormat = CSVFormat.DEFAULT
                .withHeader(CSV_HEADERS);

        try (CSVPrinter printer = new CSVPrinter(new FileWriter(getCsvOutputPath()), csvFormat)) {
            for (String[] record : csvRecords) {
                printer.printRecord((Object[]) record);
            }
            LOGGER.info("Report successfully written to: {}", getCsvOutputPath());
            LOGGER.info("Total records: {}", csvRecords.size());
        } catch (IOException e) {
            LOGGER.error("Error writing CSV file: {}", e.getMessage(), e);
        }
    }

    private String removeJsonExtension(String filename) {
        return filename.substring(0, filename.length() - NUMBER_OF_CHARACTERS);
    }

    private Map<String, String> scanDirectory(String rootPath) throws IOException {
        Map<String, String> filesMap = new HashMap<>();
        Path root = Paths.get(rootPath);

        if (!Files.exists(root)) {
            throw new IOException("Directory not found: " + rootPath);
        }

        for (String subFolder : TARGET_SUBFOLDERS) {
            Path subPath = root.resolve(subFolder);
            if (!Files.isDirectory(subPath)) {
                continue;
            }

            try (var stream = Files.list(subPath)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            filesMap.put(name, p.toAbsolutePath().toString());
                            fileSubfolderMap.put(name, subFolder);
                        });
            }
        }
        return filesMap;
    }

    private Map<String, JsonNode> loadJsonContent(Map<String, String> pathMap) throws IOException {
        Map<String, JsonNode> jsonMap = new HashMap<>();

        for (Map.Entry<String, String> entry : pathMap.entrySet()) {
            String fileName = entry.getKey();
            String filePath = entry.getValue();
            try {
                JsonNode root = OBJECT_MAPPER.readTree(new File(filePath));

                JsonNode typeNode = root.get("type");
                if (typeNode != null && typeNode.isTextual()) {
                    fileTypeMap.put(fileName, typeNode.asText());
                }

                JsonNode propDescriptors = root.get("propertyDescriptors");
                if (propDescriptors != null) {
                    jsonMap.put(fileName, propDescriptors);
                }

            } catch (Exception e) {
                LOGGER.warn("Error reading JSON {}: {}", fileName, e.getMessage());
            }
        }
        return jsonMap;
    }

    private Set<String> getDifference(Set<String> a, Set<String> b) {
        Set<String> diff = new HashSet<>(a);
        diff.removeAll(b);
        return diff;
    }

    private Set<String> getIntersection(Set<String> a, Set<String> b) {
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection;
    }

    private String getNodeText(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode f = node.get(field);
        return f.isTextual() ? f.asText() : f.toString();
    }

    private void requireLoaded() {
        if (!isLoaded) {
            throw new IllegalStateException("Data not loaded. Please call load() first.");
        }
    }

    /**
     * Returns the absolute path of the CSV output file NiFiComponentsDelta.csv.
     *
     * @return absolute path to the CSV report file
     */
    public String getCsvOutputPath()  {
        return outputDir.resolve(CSV_OUTPUT_FILE).toAbsolutePath().toString(); }

    /**
     * Returns the absolute path of the JSON type-mapping output file NiFiTypeMapping.json.
     *
     * @return absolute path to the JSON type-mapping file
     */
    public String getJsonOutputPath() {
        return outputDir.resolve(JSON_OUTPUT_FILE).toAbsolutePath().toString(); }

    /**
     * Returns an unmodifiable view of the source propertyDescriptors map.
     *
     * @return map of fileName to propertyDescriptors JsonNode for source files
     */
    public Map<String, JsonNode> getSourceJsonMap() {
        return Collections.unmodifiableMap(sourceJsonMap); }

    /**
     * Returns an unmodifiable view of the target propertyDescriptors map.
     *
     * @return map of fileName to propertyDescriptors JsonNode for target files
     */
    public Map<String, JsonNode> getTargetJsonMap() {
        return Collections.unmodifiableMap(targetJsonMap); }

    /**
     * Indicates whether load() has been called successfully.
     *
     * @return true if data has been loaded and the comparator is ready to use
     */
    public boolean isLoaded() {
        return isLoaded; }

    /**
     * Sets the output directory for CSV and JSON report files.
     * The directory will be created if it does not exist.
     *
     * @param outputPath path to the output directory
     * @throws IOException if the directory cannot be created
     */
    public void setOutputDir(String outputPath) throws IOException {
        if (outputPath == null || outputPath.isEmpty()) {
            return;
        }
        this.outputDir = Paths.get(outputPath);
        if (!Files.exists(this.outputDir)) {
            Files.createDirectories(this.outputDir);
            LOGGER.info("Created output directory: {}", this.outputDir.toAbsolutePath());
        }
    }
}
