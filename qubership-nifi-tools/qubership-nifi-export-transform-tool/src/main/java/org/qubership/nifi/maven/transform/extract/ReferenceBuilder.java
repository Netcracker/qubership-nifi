package org.qubership.nifi.maven.transform.extract;

import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.Processor;

import java.nio.file.Path;

/**
 * Builds paths to extracted configuration files.
 */
public class ReferenceBuilder {

    static final String FLOW_CONF_PREFIX = "flowConf_";
    static final String REFERENCE_PREFIX = "@";

    /**
     * Builds a file reference of the form @flowConf_flowName/groupPath/processorName/targetFilename
     * to be written into the processor property value.
     *
     * @param flow           flow containing the processor
     * @param processor      processor whose property is being extracted
     * @param targetFilename target filename from the config mapping
     * @return reference string starting with "@"
     */
    public String buildReference(FlowFile flow, Processor processor, String targetFilename) {
        return REFERENCE_PREFIX + buildRelativePath(flow, processor, targetFilename);
    }

    /**
     * Builds the absolute path to the file where the property value will be written.
     *
     * @param flow           flow containing the processor
     * @param processor      processor whose property is being extracted
     * @param targetFilename target filename from the config mapping
     * @return absolute path to the target file
     */
    public Path buildAbsoluteFilePath(FlowFile flow, Processor processor, String targetFilename) {
        String relativePath = buildRelativePath(flow, processor, targetFilename);
        return flow.getFilePath().getParent().resolve(relativePath);
    }

    /**
     * Builds the relative path:
     * flowConf_flowName/group1/.../groupN/processorName/targetFilename
     */
    private String buildRelativePath(FlowFile flow, Processor processor, String targetFilename) {
        Path relativePath = Path.of(FLOW_CONF_PREFIX + flow.getFlowName())
                .resolve(processor.getRelativePath())
                .resolve(targetFilename);

        return relativePath.toString().replace("\\", "/");
    }
}
