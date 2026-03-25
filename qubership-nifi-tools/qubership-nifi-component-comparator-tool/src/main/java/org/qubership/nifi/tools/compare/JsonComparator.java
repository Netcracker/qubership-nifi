package org.qubership.nifi.tools.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
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

public class JsonComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonComparator.class);

    private static final List<String> TARGET_SUBFOLDERS = List.of(
            "controllerService",
            "processors",
            "reportingTask"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int NUMBER_OF_CHARACTERS = 5;

    private Map<String, String> sourceFilesPathMap = new HashMap<>();
    private Map<String, String> targetFilesPathMap = new HashMap<>();
    private Map<String, JsonNode> sourceJsonMap = new HashMap<>();
    private Map<String, JsonNode> targetJsonMap = new HashMap<>();
    private Map<String, String> fileTypeMap = new HashMap<>();
    private Map<String, String> fileSubfolderMap = new HashMap<>();
    private Map<String, Map<String, String>> dictionaryMappings = new HashMap<>();
    private Map<String, Set<String>> allowedToDelete = new HashMap<>();
    private final List<String[]> csvRecords = new ArrayList<>();
    private final Map<String, Map<String, String>> typeToChangedProperties = new HashMap<>();
    private final Map<String, String> typeToFolderMap = new HashMap<>();

    private boolean isLoaded = false;

    /**
     * Loads source/target JSON trees and an optional rename dictionary.
     *
     * @param sourceRootPath root directory of the "before" version
     * @param targetRootPath root directory of the "after" version
     * @param dictionaryPath path to a YAML mapping-dictionary (may be null/empty)
     * @throws IOException if any directory or file cannot be read
     */
    public void load(String sourceRootPath, String targetRootPath, String dictionaryPath) throws IOException {
        LOGGER.info("Starting data load...");
        clearState();

        sourceFilesPathMap.putAll(scanDirectory(sourceRootPath));
        targetFilesPathMap.putAll(scanDirectory(targetRootPath));

        sourceJsonMap.putAll(loadJsonContent(sourceFilesPathMap));
        targetJsonMap.putAll(loadJsonContent(targetFilesPathMap));

        buildTypeToFolderMap();

        if (dictionaryPath != null && !dictionaryPath.isEmpty()) {
            loadDictionaryMappings(dictionaryPath);
            LOGGER.info("Dictionary mappings loaded: {} types", dictionaryMappings.size());
            LOGGER.info("Allowed-to-delete loaded: {} types", allowedToDelete.size());
        }

        isLoaded = true;
        LOGGER.info("Load completed. Source files: {}, Target files: {}",
                sourceJsonMap.size(), targetJsonMap.size());
    }

    /**
     * Runs the comparison. Must be called after {@link #load}.
     *
     * @throws IllegalStateException if load() has not been called yet
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

        LOGGER.info("Comparison finished. Total CSV records: {}, types with changes: {}",
                csvRecords.size(), typeToChangedProperties.size());
    }

    /**
     * Returns the CSV records produced by {@link #compare()}.
     *
     * @return unmodifiable list of CSV record arrays
     */
    public List<String[]> getCsvRecords() {
        return Collections.unmodifiableList(csvRecords);
    }

    /**
     * Returns the changed properties map produced by {@link #compare()}.
     *
     * @return unmodifiable map of type to changed properties
     */
    public Map<String, Map<String, String>> getTypeToChangedProperties() {
        return Collections.unmodifiableMap(typeToChangedProperties);
    }

    /**
     * Returns the mapping of component type to subfolder name.
     *
     * @return unmodifiable map of componentType to subfolder
     */
    public Map<String, String> getTypeToFolderMap() {
        return Collections.unmodifiableMap(typeToFolderMap);
    }

    /**
     * Returns an unmodifiable view of the source propertyDescriptors map.
     *
     * @return map of fileName to propertyDescriptors JsonNode for source files
     */
    public Map<String, JsonNode> getSourceJsonMap() {
        return Collections.unmodifiableMap(sourceJsonMap);
    }

    /**
     * Returns an unmodifiable view of the target propertyDescriptors map.
     *
     * @return map of fileName to propertyDescriptors JsonNode for target files
     */
    public Map<String, JsonNode> getTargetJsonMap() {
        return Collections.unmodifiableMap(targetJsonMap);
    }

    /**
     * Indicates whether load() has been called successfully.
     *
     * @return true if data has been loaded and the comparator is ready to use
     */
    public boolean isLoaded() {
        return isLoaded;
    }

    private void clearState() {
        sourceFilesPathMap.clear();
        targetFilesPathMap.clear();
        sourceJsonMap.clear();
        targetJsonMap.clear();
        fileTypeMap.clear();
        fileSubfolderMap.clear();
        dictionaryMappings.clear();
        allowedToDelete.clear();
        csvRecords.clear();
        typeToChangedProperties.clear();
        typeToFolderMap.clear();
    }

    private void buildTypeToFolderMap() {
        fileTypeMap.forEach((fileName, componentType) -> {
            String folder = fileSubfolderMap.get(fileName);
            if (componentType != null && folder != null) {
                typeToFolderMap.put(componentType, folder);
            }
        });
        LOGGER.info("Type-to-folder mappings built: {}", typeToFolderMap.size());
    }

    private void loadDictionaryMappings(String dictionaryPath) throws IOException {
        Path dictPath = Paths.get(dictionaryPath);
        validateDictionaryPath(dictPath);

        LOGGER.info("Loading dictionary from: {}", dictPath.getFileName());

        try (FileInputStream fis = new FileInputStream(dictPath.toFile())) {
            Map<String, Object> data = new Yaml().loadAs(fis, Map.class);
            if (data == null) {
                LOGGER.warn("Dictionary file is empty");
                return;
            }
            if (data.containsKey("displayNameMapping")) {
                parseMappingList(data.get("displayNameMapping"));
            }
            if (data.containsKey("propertiesAllowedToDelete")) {
                parseAllowedToDelete(data.get("propertiesAllowedToDelete"));
            }
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
     * Parses the propertiesAllowedToDelete section from the dictionary YAML.
     *
     * @param allowedObj the parsed YAML object for propertiesAllowedToDelete
     */
    @SuppressWarnings("unchecked")
    private void parseAllowedToDelete(Object allowedObj) {
        if (!(allowedObj instanceof List)) {
            return;
        }

        for (Object item : (List<?>) allowedObj) {
            if (!(item instanceof Map)) {
                continue;
            }

            ((Map<?, ?>) item).forEach((key, value) -> {
                if (!(value instanceof List)) {
                    return;
                }
                Set<String> displayNames = new HashSet<>();
                for (Object name : (List<?>) value) {
                    if (name != null) {
                        displayNames.add(name.toString().toLowerCase());
                    }
                }
                if (!displayNames.isEmpty()) {
                    allowedToDelete.put(key.toString(), displayNames);
                    LOGGER.info("Allowed to delete: {} ({} properties)",
                            key, displayNames.size());
                }
            });
        }
    }

    /**
     * Checks if a deleted property is allowed to be recorded in the JSON mapping.
     * A deleted property is recorded only if it is listed in the
     * propertiesAllowedToDelete section of the dictionary for its component type.
     *
     * @param componentType full component type (e.g. org.apache.nifi.dbcp.DBCPConnectionPool)
     * @param displayName   the displayName of the deleted property
     * @return true if the property is in the allow-list
     */
    private boolean isDeleteAllowed(String componentType, String displayName) {
        if (allowedToDelete.isEmpty()) {
            return false;
        }
        String shortType = getShortTypeName(componentType);
        if (shortType == null) {
            return false;
        }
        Set<String> allowed = allowedToDelete.get(shortType);
        if (allowed == null) {
            return false;
        }
        return displayName != null && allowed.contains(displayName.toLowerCase());
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
            processAddedTargetProperties(fileName, componentFolder,
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
                if (isDeleteAllowed(componentType, sourceProp.getDisplayName())) {
                    recordDeleted(componentType, sourceProp.getApiName());
                }
            }
        }
    }

    private void processAddedTargetProperties(String fileName,
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
        typeToChangedProperties.computeIfAbsent(componentType, k -> new HashMap<>())
                .put(oldApiName, newApiName);
    }

    private void recordDeleted(String componentType, String apiName) {
        typeToChangedProperties.computeIfAbsent(componentType, k -> new HashMap<>())
                .put(apiName, null);
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
                LOGGER.warn("Error reading JSON {}: {}", fileName, e.getMessage(), e);
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
}
