package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

/**
 * Represents a single exported NiFi flow JSON file.
 */
public class FlowFile {

    private final Path filePath;

    /**
     * Full JSON tree of the file. Mutable — processor property changes
     * are applied in-place via ObjectNode references in ProcessorProperty.
     */
    private final JsonNode rootNode;

    /**
     * Root ProcessGroup (contents of "flowContents").
     * One file corresponds to exactly one root group.
     */
    private final ProcessGroup rootGroup;

    public FlowFile(Path filePath, JsonNode rootNode, ProcessGroup rootGroup) {
        this.filePath = filePath;
        this.rootNode = rootNode;
        this.rootGroup = rootGroup;
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
