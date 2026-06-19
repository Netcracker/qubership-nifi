package org.qubership.nifi.maven.transform.flow;

import org.qubership.nifi.tools.jsonformat.JsonFormatReformatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes a modified FlowFile back to disk, reproducing the original JSON formatting.
 */
public class FlowWriter {

    private final JsonFormatReformatter reformatter = new JsonFormatReformatter();

    /**
     * Saves the FlowFile to its original path on disk.
     * Property value changes are already applied in-place to rootNode.
     * The output uses the same JSON formatting that was detected when the file was read.
     *
     * @param flow modified FlowFile to write
     * @throws IOException if the file cannot be written
     */
    public void write(FlowFile flow) throws IOException {
        String json = reformatter.write(flow.getRootNode(), flow.getDetectedFormat());
        Files.writeString(flow.getFilePath(), json, StandardCharsets.UTF_8);
    }
}
