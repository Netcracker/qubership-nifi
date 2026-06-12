package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.tools.jsonformat.JsonFormat;
import org.qubership.nifi.tools.jsonformat.JsonFormatDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads exported NiFi flow JSON files and builds the object model.
 */
public class FlowReader {

    private static final String FLOW_CONF_PREFIX = "flowConf_";

    private final ObjectMapper jsonMapper;
    private final Set<String> configuredTypes;

    /**
     * Creates a FlowReader that uses the given Jackson mapper for JSON parsing.
     *
     * @param jsonMapperValue Jackson ObjectMapper used to parse flow JSON files
     * @param config          plugin config; processor type FQNs are pre-computed once here
     */
    public FlowReader(final ObjectMapper jsonMapperValue, final PluginConfig config) {
        this.jsonMapper = jsonMapperValue;
        this.configuredTypes = config.getProcessorTypeFqns();
    }

    /**
     * Reads a single JSON file and builds a FlowFile.
     * Returns an empty Optional if the file does not contain a flowContents field,
     * meaning it is not a NiFi flow export - callers should skip such files.
     *
     * @param flowFilePath path to the JSON file to read
     * @return Optional containing the parsed FlowFile, or empty if flowContents is absent
     * @throws IOException if the file cannot be read or contains invalid JSON
     */
    public Optional<FlowFile> read(Path flowFilePath) throws IOException {
        String content = Files.readString(flowFilePath);
        JsonFormat detectedFormat = JsonFormatDetector.detect(content);
        JsonNode rootNode = jsonMapper.readTree(content);

        JsonNode flowContentsNode = rootNode.get("flowContents");
        if (flowContentsNode == null || flowContentsNode.isNull()) {
            return Optional.empty();
        }

        Map<String, List<Processor>> processorsByType = new HashMap<>();
        ProcessGroup rootGroup = parseProcessGroup(
                flowContentsNode, null, processorsByType);

        return Optional.of(new FlowFile(
                flowFilePath, rootNode, rootGroup, processorsByType, detectedFormat));
    }

    /**
     * Recursively walks the directory and collects paths to all *.json flow files.
     * Skips directories whose name starts with "flowConf_" — those are created by the plugin.
     *
     * @param exportDir root directory containing exported flow files
     * @return list of paths to flow JSON files
     * @throws IOException if the directory cannot be walked
     */
    public List<Path> findFlowPaths(Path exportDir) throws IOException {
        try (Stream<Path> stream = Files.walk(exportDir)) {
            return stream
                    .filter(path -> !isInsideFlowConfDir(path, exportDir))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .toList();
        }
    }

    /**
     * Recursively builds a ProcessGroup from a JSON node.
     *
     * @param node             JSON node of the group
     * @param parent           parent group, or null for the root group
     * @param processorsByType accumulator map being built during traversal
     * @return parsed ProcessGroup with nested processors and child groups
     */
    private ProcessGroup parseProcessGroup(JsonNode node,
                                           ProcessGroup parent,
                                           Map<String, List<Processor>> processorsByType) {
        String name = getTextOrEmpty(node, "name");
        String identifier = getTextOrEmpty(node, "identifier");
        boolean versioned = node.has("versionedFlowCoordinates")
                && !node.get("versionedFlowCoordinates").isNull();

        List<Processor> processors = new ArrayList<>();
        List<ProcessGroup> children = new ArrayList<>();

        ProcessGroup group = new ProcessGroup(
                name, identifier, processors, children, parent, versioned);

        if (!versioned) {
            parseProcessors(node, group, processors, processorsByType);
            parseChildren(node, group, children, processorsByType);
        }

        return group;
    }

    /**
     * Parses processors from the "processors" array node.
     * Only processors whose type is in configuredTypes are added.
     * Each parsed processor is immediately added to processorsByType.
     *
     * @param groupNode        JSON node of the process group
     * @param group            process group that owns the parsed processors
     * @param processors       list to which parsed processors are appended
     * @param processorsByType accumulator map keyed by processor type FQN
     */
    private void parseProcessors(JsonNode groupNode,
                                 ProcessGroup group,
                                 List<Processor> processors,
                                 Map<String, List<Processor>> processorsByType) {
        JsonNode processorsNode = groupNode.get("processors");
        if (processorsNode == null || !processorsNode.isArray()) {
            return;
        }
        for (JsonNode processorNode : processorsNode) {
            String typeFqn = getTextOrEmpty(processorNode, "type");
            if (configuredTypes.contains(typeFqn)) {
                Processor processor = parseProcessor(processorNode, group);
                processors.add(processor);
                processorsByType
                        .computeIfAbsent(typeFqn, k -> new ArrayList<>())
                        .add(processor);
            }
        }
    }

    /**
     * Parses nested groups from the "processGroups" array node and adds them to the list.
     *
     * @param groupNode        JSON node of the parent process group
     * @param group            parent process group
     * @param children         list to which parsed child groups are appended
     * @param processorsByType accumulator map passed through to recursive calls
     */
    private void parseChildren(JsonNode groupNode,
                               ProcessGroup group,
                               List<ProcessGroup> children,
                               Map<String, List<Processor>> processorsByType) {
        JsonNode childrenNode = groupNode.get("processGroups");
        if (childrenNode == null || !childrenNode.isArray()) {
            return;
        }
        for (JsonNode childNode : childrenNode) {
            children.add(parseProcessGroup(childNode, group, processorsByType));
        }
    }

    /**
     * Builds a Processor from a JSON node.
     * If "properties" is absent, a detached empty ObjectNode is used — it is NOT inserted
     * into the tree, so the JSON is not modified unless a property value is actually written.
     *
     * @param node        JSON node of the processor
     * @param parentGroup process group that owns this processor
     * @return parsed Processor with mutable properties node
     */
    private Processor parseProcessor(JsonNode node, ProcessGroup parentGroup) {
        String name = getTextOrEmpty(node, "name");
        String typeFqn = getTextOrEmpty(node, "type");
        String identifier = getTextOrEmpty(node, "identifier");

        JsonNode propsRaw = node.get("properties");
        ObjectNode propertiesNode = (propsRaw != null && propsRaw.isObject())
                ? (ObjectNode) propsRaw
                : jsonMapper.createObjectNode();

        return new Processor(name, typeFqn, identifier, propertiesNode, parentGroup);
    }

    /**
     * Returns true if the path is located inside a flowConf_* directory.
     *
     * @param path      path to test
     * @param exportDir root export directory used as the relativization base
     * @return true if any segment of the path relative to exportDir starts with "flowConf_"
     */
    private boolean isInsideFlowConfDir(Path path, Path exportDir) {
        Path relative = exportDir.relativize(path);
        for (Path segment : relative) {
            if (segment.toString().startsWith(FLOW_CONF_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the text value of the named field, or an empty string if the field
     * is absent or null.
     *
     * @param node      JSON node to read from
     * @param fieldName name of the field to retrieve
     * @return text value of the field, or "" if absent or null
     */
    private String getTextOrEmpty(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : "";
    }
}
