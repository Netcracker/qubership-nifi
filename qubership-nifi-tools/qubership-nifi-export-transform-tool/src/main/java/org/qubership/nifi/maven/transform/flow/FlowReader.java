package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * @return parsed FlowFile with object model and mutable JSON tree
     * @throws IOException              if the file cannot be read or contains invalid JSON
     * @throws IllegalArgumentException if "flowContents" is missing from the JSON
     */
    public FlowFile read(Path flowFilePath) throws IOException {
        JsonNode rootNode = jsonMapper.readTree(flowFilePath.toFile());

        JsonNode flowContentsNode = rootNode.get("flowContents");
        if (flowContentsNode == null || flowContentsNode.isNull()) {
            throw new IllegalArgumentException(
                    "Missing 'flowContents' in flow file: " + flowFilePath.toAbsolutePath());
        }

        ProcessGroup rootGroup = parseProcessGroup(flowContentsNode, null);
        return new FlowFile(flowFilePath, rootNode, rootGroup);
    }

    /**
     * Recursively walks the directory and reads all *.json files.
     * Skips directories whose name starts with "flowConf_" — those are created by the plugin.
     *
     * @param exportDir root directory containing exported flow files
     * @return list of parsed FlowFile instances
     * @throws IOException if the directory cannot be walked or a file cannot be read
     */
    public List<FlowFile> readAll(Path exportDir) throws IOException {
        List<FlowFile> flows = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(exportDir)) {
            List<Path> jsonFiles = stream
                    .filter(path -> !isInsideFlowConfDir(path, exportDir))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .toList();

            for (Path jsonFile : jsonFiles) {
                flows.add(read(jsonFile));
            }
        }

        return flows;
    }

    /**
     * Recursively builds a ProcessGroup from a JSON node.
     *
     * @param node   JSON node of the group
     * @param parent parent group, or null for the root group
     */
    private ProcessGroup parseProcessGroup(JsonNode node, ProcessGroup parent) {
        String name = getTextOrEmpty(node, "name");
        String identifier = getTextOrEmpty(node, "identifier");
        boolean versioned = node.has("versionedFlowCoordinates")
                && !node.get("versionedFlowCoordinates").isNull();

        List<Processor> processors = new ArrayList<>();
        List<ProcessGroup> children = new ArrayList<>();

        ProcessGroup group = new ProcessGroup(
                name, identifier, processors, children, parent, versioned);

        if (!versioned) {
            parseProcessors(node, group, processors);
            parseChildren(node, group, children);
        }

        return group;
    }

    /**
     * Parses processors from the "processors" array node and adds them to the list.
     */
    private void parseProcessors(JsonNode groupNode, ProcessGroup group,
                                 List<Processor> processors) {
        JsonNode processorsNode = groupNode.get("processors");
        if (processorsNode == null || !processorsNode.isArray()) {
            return;
        }
        for (JsonNode processorNode : processorsNode) {
            processors.add(parseProcessor(processorNode, group));
        }
    }

    /**
     * Parses nested groups from the "processGroups" array node and adds them to the list.
     */
    private void parseChildren(JsonNode groupNode, ProcessGroup group,
                               List<ProcessGroup> children) {
        JsonNode childrenNode = groupNode.get("processGroups");
        if (childrenNode == null || !childrenNode.isArray()) {
            return;
        }
        for (JsonNode childNode : childrenNode) {
            children.add(parseProcessGroup(childNode, group));
        }
    }

    /**
     * Builds a Processor from a JSON node..
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
     * Such directories are created by the plugin and are not NiFi flow files.
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
