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

    /**
     * Constructor for class FlowFile.
     *
     * @param filePathValue         path to the source JSON file on disk
     * @param rootNodeValue         full JSON tree of the file
     * @param rootGroupValue        root process group parsed from the flowContents section
     * @param processorsByTypeValue map of processor type FQN to matching processors, built by FlowReader
     */
    public FlowFile(final Path filePathValue,
                    final JsonNode rootNodeValue,
                    final ProcessGroup rootGroupValue,
                    final Map<String, List<Processor>> processorsByTypeValue) {
        this.filePath = filePathValue;
        this.rootNode = rootNodeValue;
        this.rootGroup = rootGroupValue;
        this.processorsByType = Collections.unmodifiableMap(processorsByTypeValue);
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
     * Returns the path to the source JSON file on disk.
     *
     * @return path as supplied at construction time
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Returns the full JSON tree of the flow file.
     * Property values are modified in-place through ObjectNode references
     * held by ProcessorProperty instances during Extract and Build operations.
     *
     * @return root JSON node of the file
     */
    public JsonNode getRootNode() {
        return rootNode;
    }

    /**
     * Returns the root process group, corresponding to the flowContents
     * section of the exported NiFi flow JSON.
     *
     * @return root ProcessGroup of this flow
     */
    public ProcessGroup getRootGroup() {
        return rootGroup;
    }
}
