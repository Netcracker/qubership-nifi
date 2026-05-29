package org.qubership.nifi.maven.transform.extract;

import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.Processor;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds paths to extracted configuration files.
 *
 */
public class ReferenceBuilder {

    static final String FLOW_CONF_PREFIX = "flowConf_";
    static final String REFERENCE_PREFIX = "@";

    // Characters not allowed in file and directory names (Windows + Linux)
    private static final Pattern INVALID_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");

    /**
     * Builds a file reference of the form @flowConf_flowName/groupPath/processorName/targetFilename
     * to be written into the processor property value.
     *
     * @param flow           flow containing the processor
     * @param processor      processor whose property is being extracted
     * @param targetFilename target filename from the config mapping
     * @return reference string starting with "@"
     * @throws ExtractException if the flow name, group name, or processor name
     *                          contains characters not allowed in file system paths
     */
    public String buildReference(FlowFile flow, Processor processor, String targetFilename)
            throws ExtractException {
        return REFERENCE_PREFIX + buildRelativePath(flow, processor, targetFilename);
    }

    /**
     * Builds the absolute path to the file where the property value will be written.
     *
     * @param flow           flow containing the processor
     * @param processor      processor whose property is being extracted
     * @param targetFilename target filename from the config mapping
     * @return absolute path to the target file
     * @throws ExtractException if the flow name, group name, or processor name
     *                          contains characters not allowed in file system paths
     */
    public Path buildAbsoluteFilePath(FlowFile flow, Processor processor, String targetFilename)
            throws ExtractException {
        Path flowDir = flow.getFilePath().getParent();
        String relativePath = buildRelativePath(flow, processor, targetFilename);

        Path result = flowDir;
        for (String segment : relativePath.split("/")) {
            result = result.resolve(segment);
        }
        return result;
    }

    /**
     * Builds the relative path:
     * flowConf_flowName/group1/.../groupN/processorName/targetFilename
     */
    private String buildRelativePath(FlowFile flow, Processor processor, String targetFilename)
            throws ExtractException {

        validateSegment(flow.getFlowName(), "flow name");

        List<String> groupSegments = processor.getParentGroup().getPathSegments();
        for (String segment : groupSegments) {
            validateSegment(segment, "process group name");
        }

        validateSegment(processor.getName(), "processor name");

        StringBuilder sb = new StringBuilder();
        sb.append(FLOW_CONF_PREFIX).append(flow.getFlowName());
        for (String segment : groupSegments) {
            sb.append("/").append(segment);
        }
        sb.append("/").append(processor.getName());
        sb.append("/").append(targetFilename);

        return sb.toString();
    }

    /**
     * Validates that a path segment does not contain characters
     * that are not allowed in file system paths.
     *
     * @param segment     segment to validate
     * @param segmentType description of the segment type for the error message
     * @throws ExtractException if invalid characters are found
     */
    private void validateSegment(String segment, String segmentType) throws ExtractException {
        if (INVALID_CHARS.matcher(segment).find()) {
            throw new ExtractException(String.format(
                    "Invalid characters in %s '%s'. " +
                            "The following characters are not allowed in file system paths: / \\ : * ? \" < > |",
                    segmentType, segment));
        }
    }
}
