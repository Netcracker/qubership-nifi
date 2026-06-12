package org.qubership.nifi.maven.transform.build;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;
import org.qubership.nifi.maven.transform.extract.PropertyResolver;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Performs the Build operation.
 *
 * Reads extracted configuration files and writes their contents back
 * into the processor properties of the exported NiFi flow JSON files.
 * Replaces file references of the form @path with the actual file content.
 *
 * All BuildExceptions are collected during processing and reported together
 * at the end. This allows the user to see and fix all problems in one run.
 * Only IOException stops execution immediately.
 */
public class BuildService {

    private final Log log;
    private final FlowReader flowReader;
    private final FlowWriter flowWriter;
    private final FileSystemService fileSystem;
    private final PropertyResolver propertyResolver;
    private final ReferenceResolver referenceResolver;
    private final CleanupService cleanupService;

    /**
     * Constructs a BuildService with all required dependencies.
     *
     * @param logValue               Maven logger
     * @param flowReaderValue        reads flow files from disk
     * @param flowWriterValue        writes modified flow files back to disk
     * @param fileSystemValue        file system operations
     * @param propertyResolverValue  resolves processor properties by name or regex
     * @param referenceResolverValue resolves file references in property values
     * @param cleanupServiceValue    deletes extracted config directories after build
     */
    public BuildService(final Log logValue,
                        final FlowReader flowReaderValue,
                        final FlowWriter flowWriterValue,
                        final FileSystemService fileSystemValue,
                        final PropertyResolver propertyResolverValue,
                        final ReferenceResolver referenceResolverValue,
                        final CleanupService cleanupServiceValue) {
        this.log = logValue;
        this.flowReader = flowReaderValue;
        this.flowWriter = flowWriterValue;
        this.fileSystem = fileSystemValue;
        this.propertyResolver = propertyResolverValue;
        this.referenceResolver = referenceResolverValue;
        this.cleanupService = cleanupServiceValue;
    }

    /**
     * Runs Build on all flow files found in the given directory.
     *
     * All BuildExceptions are collected across all flows and processors
     * and reported together at the end. Processing continues even if errors occur.
     * Only IOException stops execution immediately.
     * If --delete is true, cleanup runs only after all flows are processed without errors.
     *
     * @param config    parsed plugin config
     * @param exportDir root directory containing exported NiFi flow files
     * @param delete    whether to delete extracted config files after successful build
     * @throws BuildException if any build errors were collected
     * @throws IOException    if a file cannot be read or written
     */
    public void build(PluginConfig config, Path exportDir, boolean delete)
            throws BuildException, IOException {

        log.info("Starting Build from " + exportDir.toAbsolutePath());

        List<Path> flowPaths = flowReader.findFlowPaths(exportDir);
        log.info("Found " + flowPaths.size() + " flow file(s) to process");

        List<BuildException> collectedErrors = new ArrayList<>();

        for (Path flowPath : flowPaths) {
            java.util.Optional<FlowFile> flowOpt = flowReader.read(flowPath);
            if (flowOpt.isEmpty()) {
                log.debug("Skipping " + flowPath + ": no 'flowContents' found, not a NiFi flow file.");
                continue;
            }
            FlowFile flow = flowOpt.get();
            log.info("Processing flow: " + flow.getFlowName());

            int errorsBefore = collectedErrors.size();
            int modified = processFlow(flow, config, collectedErrors);
            boolean flowHasErrors = collectedErrors.size() > errorsBefore;

            if (!flowHasErrors && modified > 0) {
                flowWriter.write(flow);
            } else if (!flowHasErrors) {
                log.debug("Flow '" + flow.getFlowName() + "' had no properties to build, skipping write.");
            }
        }

        if (collectedErrors.isEmpty() && delete) {
            log.info("Cleaning up extracted config directories...");
            cleanupService.cleanup(exportDir);
        }

        reportErrors(collectedErrors);
    }

    /**
     * Processes a single flow file: for each processor type defined in the config,
     * restores property values from extracted files.
     *
     * @param flow            flow file to process
     * @param config          parsed plugin config
     * @param collectedErrors list to which build errors are appended
     * @return number of properties actually restored (property.setValue called)
     */
    private int processFlow(final FlowFile flow, final PluginConfig config,
                             final List<BuildException> collectedErrors)
            throws IOException {

        int modified = 0;

        for (ProcessorTypeConfig typeConfig : config.getProcessorTypes()) {
            List<Processor> processors = flow.getProcessorsByType(
                    typeConfig.getProcessorTypeFqn());

            if (processors.isEmpty()) {
                log.debug("No processors of type '" + typeConfig.getProcessorTypeFqn()
                        + "' found in flow '" + flow.getFlowName() + "'");
                continue;
            }

            for (Processor processor : processors) {
                for (PropertyMapping mapping : typeConfig.getPropertyMappings()) {
                    try {
                        if (buildFromProcessor(flow, processor, mapping)) {
                            modified++;
                        }
                    } catch (BuildException e) {
                        collectedErrors.add(e);
                        log.debug(String.format(
                                "Skipping processor '%s' (id: %s, group: '%s', groupId: %s, "
                                        + "flow: '%s', flowPath: '%s') due to error: %s",
                                processor.getName(),
                                processor.getIdentifier(),
                                processor.getParentGroup().getName(),
                                processor.getParentGroup().getIdentifier(),
                                flow.getFlowName(),
                                flow.getFilePath(),
                                e.getMessage()));
                    }
                }
            }
        }

        return modified;
    }

    /**
     * Restores a single property of a single processor from its extracted file.
     * Resolves the property by name or regex; reads file content for references;
     * checks for conflict with existing extracted file for inline values;
     * skips with a warning if the property is empty or not found.
     *
     * @param flow      flow containing the processor
     * @param processor processor whose property is being restored
     * @param mapping   property mapping defining the property name and target filename
     * @return true if the property value was restored (property.setValue called)
     * @throws BuildException if the referenced file does not exist,
     *                        or an inline value conflicts with an existing extracted file
     */
    private boolean buildFromProcessor(final FlowFile flow,
                                        final Processor processor,
                                        final PropertyMapping mapping)
            throws BuildException, IOException {

        Optional<ProcessorProperty> propertyOpt = propertyResolver.resolve(processor, mapping);

        if (propertyOpt.isEmpty()) {
            return false;
        }

        ProcessorProperty property = propertyOpt.get();

        if (property.isEmpty()) {
            log.warn(String.format(
                    "Property '%s' of processor '%s' (id: %s, group: '%s', groupId: %s, flow: '%s') "
                            + "is empty or null. Skipping.",
                    property.getName(),
                    processor.getName(),
                    processor.getIdentifier(),
                    processor.getParentGroup().getName(),
                    processor.getParentGroup().getIdentifier(),
                    flow.getFlowName()));
            return false;
        }

        if (property.isReference()) {
            Path filePath = referenceResolver.resolve(flow, processor, property);
            String content = fileSystem.readText(filePath);
            property.setValue(content);
            log.info(String.format("Restored property '%s' of processor '%s' from %s",
                    property.getName(), processor.getName(), filePath));
            return true;
        } else {
            referenceResolver.checkConflict(flow, processor, property,
                    mapping.getTargetFilename());
            log.debug(String.format(
                    "Property '%s' of processor '%s' (id: %s, group: '%s', groupId: %s, "
                            + "flow: '%s', file: '%s') has an inline value and no extracted file "
                            + "- skipping. Run Extract first if this processor should be managed.",
                    property.getName(),
                    processor.getName(),
                    processor.getIdentifier(),
                    processor.getParentGroup().getName(),
                    processor.getParentGroup().getIdentifier(),
                    flow.getFlowName(),
                    flow.getFilePath()));
            return false;
        }
    }

    /**
     * Logs all collected errors and throws a single BuildException summarizing the failures.
     * Each error is logged individually so the user can see all problems at once.
     *
     * @param errors list of collected build errors
     * @throws BuildException if the list is not empty
     */
    private void reportErrors(List<BuildException> errors) throws BuildException {
        if (errors.isEmpty()) {
            return;
        }

        log.error("Build completed with " + errors.size() + " error(s):");
        for (int i = 0; i < errors.size(); i++) {
            log.error("  [" + (i + 1) + "] " + errors.get(i).getMessage());
        }

        throw new BuildException(
            "Build failed with " + errors.size() + " error(s). See log for details.");
    }
}
