package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a single exported NiFi flow JSON file.
 */
public class FlowFile {

    private final Path filePath;

    /**
     * Full JSON tree of the file. Mutable - processor property changes
     * are applied in-place via ObjectNode references in ProcessorProperty.
     */
    private final JsonNode rootNode;

    /**
     * Root ProcessGroup (contents of "flowContents").
     */
    private final ProcessGroup rootGroup;

    /**
     * Pre-built map of processors grouped by their fully qualified type name.
     * Contains only processors whose types are defined in the plugin config.
     * Built once in FlowReader to avoid repeated tree traversals.
     */
    private final Map<String, List<Processor>> processorsByType;

    public FlowFile(Path filePath,
                    JsonNode rootNode,
                    ProcessGroup rootGroup,
                    Map<String, List<Processor>> processorsByType) {
        this.filePath = filePath;
        this.rootNode = rootNode;
        this.rootGroup = rootGroup;
        this.processorsByType = Collections.unmodifiableMap(processorsByType);
    }

    /**
     * Returns the flow name — the file name without extension.
     *
     * @return flow name derived from the file name
     */
    public String getFlowName() {
        String fileName = filePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * Returns all processors of the given type found in this flow.
     * Returns an empty list if no processors of that type are present.
     *
     * @param typeFqn fully qualified processor type name
     * @return unmodifiable list of processors of that type
     */
    public List<Processor> getProcessorsByType(String typeFqn) {
        return processorsByType.getOrDefault(typeFqn, Collections.emptyList());
    }

    /**
     * Returns the pre-built map of all configured processors grouped by type FQN.
     *
     * @return unmodifiable map of typeFqn to list of processors
     */
    public Map<String, List<Processor>> getProcessorsByType() {
        return processorsByType;
    }

    public Path getFilePath() {
        return filePath;
    }

    public JsonNode getRootNode() {
        return rootNode;
    }

    public ProcessGroup getRootGroup() {
        return rootGroup;
    }
}
