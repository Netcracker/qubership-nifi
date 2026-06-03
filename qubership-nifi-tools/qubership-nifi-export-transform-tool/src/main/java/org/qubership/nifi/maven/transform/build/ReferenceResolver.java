package org.qubership.nifi.maven.transform.build;

import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves file references in processor properties during the Build operation.
 */
public class ReferenceResolver {

    /**
     * Resolves the reference in the given property to an absolute file path.
     * Used when the property value is a reference of the form @path.
     *
     * @param flow     flow file containing the processor
     * @param property processor property with a reference value
     * @return absolute path to the referenced file
     * @throws BuildException if the referenced file does not exist
     */
    public Path resolve(FlowFile flow, ProcessorProperty property) throws BuildException {
        String referencePath = property.getReferencePath();
        Path absolutePath = buildAbsolutePath(flow, referencePath);

        if (!Files.isRegularFile(absolutePath)) {
            throw new BuildException(String.format(
                "Referenced file '%s' does not exist for property '%s'. " +
                "Run Extract first to generate the configuration files.",
                absolutePath, property.getName()));
        }

        return absolutePath;
    }

    /**
     * Checks for a conflict between an inline property value and an existing extracted file.
     * Called when the property value is not a reference (inline value).
     *
     * If an extracted file already exists on disk for this property, the state is ambiguous:
     * it is unclear whether the inline value or the file content should be used.
     *
     * @param flow           flow file containing the processor
     * @param processor      processor owning the property
     * @param property       processor property with an inline value
     * @param targetFilename target filename from the config mapping
     * @throws BuildException if an extracted file exists alongside an inline value
     */
    public void checkConflict(FlowFile flow,
                               Processor processor,
                               ProcessorProperty property,
                               String targetFilename) throws BuildException {

        Path extractedFile = buildExtractedFilePath(flow, processor, targetFilename);

        if (Files.isRegularFile(extractedFile)) {
            throw new BuildException(String.format(
                "Property '%s' of processor '%s' has an inline value, " +
                "but an extracted file already exists at '%s'. " +
                "This is ambiguous: remove either the inline value or the extracted file.",
                property.getName(), processor.getName(), extractedFile));
        }
    }


    /**
     * Builds the absolute path from a reference string
     * relative to the flow file location.
     */
    private Path buildAbsolutePath(FlowFile flow, String referencePath) {
        Path flowDir = flow.getFilePath().getParent();
        Path result = flowDir;
        for (String segment : referencePath.split("/")) {
            result = result.resolve(segment);
        }
        return result;
    }

    /**
     * Builds the expected extracted file path for a given processor and target filename.
     * Mirrors the path structure built by ReferenceBuilder during Extract.
     */
    private Path buildExtractedFilePath(FlowFile flow, Processor processor,
                                         String targetFilename) {
        Path flowDir = flow.getFilePath().getParent();
        Path result = flowDir.resolve("flowConf_" + flow.getFlowName());

        for (String segment : processor.getParentGroup().getPathSegments()) {
            result = result.resolve(segment);
        }

        return result
            .resolve(processor.getName())
            .resolve(targetFilename);
    }
}
