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
     * @param flow      flow file containing the processor
     * @param processor processor owning the property
     * @param property  processor property with a reference value
     * @return absolute path to the referenced file
     * @throws BuildException if the referenced file does not exist
     */
    public Path resolve(FlowFile flow, Processor processor,
                        ProcessorProperty property) throws BuildException {
        String referencePath = property.getReferencePath();
        Path absolutePath = buildAbsolutePath(flow, referencePath);

        if (!Files.isRegularFile(absolutePath)) {
            throw new BuildException(String.format(
                    "Referenced file '%s' does not exist for property '%s' "
                            + "of processor '%s' (id: %s, group: '%s', groupId: %s, flow: '%s'). "
                            + "Run Extract first to generate the configuration files.",
                    absolutePath,
                    property.getName(),
                    processor.getName(),
                    processor.getIdentifier(),
                    processor.getParentGroup().getName(),
                    processor.getParentGroup().getIdentifier(),
                    flow.getFilePath()));
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
                    "Property '%s' of processor '%s' has an inline value, "
                            + "but an extracted file already exists at '%s'. "
                            + "This is ambiguous: remove either the inline value or the extracted file "
                            + "(flow file: '%s').",
                    property.getName(), processor.getName(), extractedFile,
                    flow.getFilePath()));
        }
    }

    /**
     * Builds an absolute path by resolving a reference string relative to the
     * directory containing the flow file.
     *
     * Rejects paths that escape the flow directory (e.g. containing "..").
     *
     * @param flow          the flow file whose parent directory is used as the base
     * @param referencePath a relative path string (e.g. "flowConf_foo/bar/file.json")
     *                      taken from a @path reference value
     * @return the absolute Path obtained by resolving referencePath
     *         against the flow file's parent directory
     * @throws BuildException if the resolved path escapes the flow directory
     */
    private Path buildAbsolutePath(FlowFile flow, String referencePath) throws BuildException {
        Path base = flow.getFilePath().getParent().toAbsolutePath().normalize();
        Path resolved = base.resolve(referencePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new BuildException(
                    "Reference path '" + referencePath
                    + "' escapes the export directory. Only paths within the flow directory are allowed.");
        }
        return resolved;
    }

    /**
     * Builds the expected extracted file path for a given processor and target filename.
     * Mirrors the path structure built by ReferenceBuilder during Extract.
     *
     * @param flow           the flow file whose parent directory is used as the base
     * @param processor      the processor whose parent group path segments are included
     * @param targetFilename the filename of the extracted configuration file
     * @return the absolute Path where the extracted file is expected to reside
     */
    private Path buildExtractedFilePath(FlowFile flow, Processor processor,
                                        String targetFilename) {
        return flow.getFilePath().getParent()
                .resolve("flowConf_" + flow.getFlowName())
                .resolve(processor.getRelativePath())
                .resolve(targetFilename);
    }
}
