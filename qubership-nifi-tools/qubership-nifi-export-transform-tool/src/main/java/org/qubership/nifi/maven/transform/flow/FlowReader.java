package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.maven.transform.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads exported NiFi flow JSON files and builds the object model.
 */
public class FlowReader {

    private static final String FLOW_CONF_PREFIX = "flowConf_";

    private final ObjectMapper jsonMapper;

    public FlowReader(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * Reads a single JSON file and builds a FlowFile.
     *
     * @param flowFilePath path to the flow JSON file
     * @param config       plugin config used to filter processor types
     * @return parsed FlowFile with object model, mutable JSON tree and processorsByType map
     * @throws IOException              if the file cannot be read or contains invalid JSON
     * @throws IllegalArgumentException if "flowContents" is missing from the JSON
     */
    public FlowFile read(Path flowFilePath, PluginConfig config) throws IOException {
        Set<String> configuredTypes = config.getProcessorTypes().stream()
                .map(t -> t.getProcessorTypeFqn())
                .collect(Collectors.toSet());

        JsonNode rootNode = jsonMapper.readTree(flowFilePath.toFile());

        JsonNode flowContentsNode = rootNode.get("flowContents");
        if (flowContentsNode == null || flowContentsNode.isNull()) {
            throw new IllegalArgumentException(
                    "Missing 'flowContents' in flow file: " + flowFilePath.toAbsolutePath());
        }

        Map<String, List<Processor>> processorsByType = new HashMap<>();
        ProcessGroup rootGroup = parseProcessGroup(
                flowContentsNode, null, configuredTypes, processorsByType);

        return new FlowFile(flowFilePath, rootNode, rootGroup, processorsByType);
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
     * @param configuredTypes  set of processor type FQNs defined in the config
     * @param processorsByType accumulator map being built during traversal
     */
    private ProcessGroup parseProcessGroup(JsonNode node,
                                           ProcessGroup parent,
                                           Set<String> configuredTypes,
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
            parseProcessors(node, group, processors, configuredTypes, processorsByType);
            parseChildren(node, group, children, configuredTypes, processorsByType);
        }

        return group;
    }

    /**
     * Parses processors from the "processors" array node.
     * Only processors whose type is in configuredTypes are added.
     * Each parsed processor is immediately added to processorsByType.
     */
    private void parseProcessors(JsonNode groupNode,
                                 ProcessGroup group,
                                 List<Processor> processors,
                                 Set<String> configuredTypes,
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
     */
    private void parseChildren(JsonNode groupNode,
                               ProcessGroup group,
                               List<ProcessGroup> children,
                               Set<String> configuredTypes,
                               Map<String, List<Processor>> processorsByType) {
        JsonNode childrenNode = groupNode.get("processGroups");
        if (childrenNode == null || !childrenNode.isArray()) {
            return;
        }
        for (JsonNode childNode : childrenNode) {
            children.add(parseProcessGroup(childNode, group, configuredTypes, processorsByType));
        }
    }

    /**
     * Builds a Processor from a JSON node.
     * If "properties" is absent, an empty ObjectNode is created and inserted into the tree.
     */
    private Processor parseProcessor(JsonNode node, ProcessGroup parentGroup) {
        String name = getTextOrEmpty(node, "name");
        String typeFqn = getTextOrEmpty(node, "type");
        String identifier = getTextOrEmpty(node, "identifier");

        ObjectNode propertiesNode;
        JsonNode propsRaw = node.get("properties");
        if (propsRaw != null && propsRaw.isObject()) {
            propertiesNode = (ObjectNode) propsRaw;
        } else {
            propertiesNode = jsonMapper.createObjectNode();
            ((ObjectNode) node).set("properties", propertiesNode);
        }

        return new Processor(name, typeFqn, identifier, propertiesNode, parentGroup);
    }

    /**
     * Returns true if the path is located inside a flowConf_* directory.
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

    private String getTextOrEmpty(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : "";
    }
}
