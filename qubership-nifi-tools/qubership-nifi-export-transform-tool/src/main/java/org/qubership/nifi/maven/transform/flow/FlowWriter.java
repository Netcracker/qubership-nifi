package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Writes a modified FlowFile back to disk.
 */
public class FlowWriter {

    private final ObjectMapper jsonMapper;

    public FlowWriter(final ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * Saves the FlowFile to its original path on disk.
     * Property value changes are already applied in-place to rootNode.
     *
     * @param flow modified FlowFile to write
     * @throws IOException if the file cannot be written
     */
    public void write(FlowFile flow) throws IOException {
        jsonMapper.writeValue(flow.getFilePath().toFile(), flow.getRootNode());
    }
}
