package org.qubership.nifi.maven.transform.build;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.exception.ExtractException;
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

    public BuildService(Log log,
                        FlowReader flowReader,
                        FlowWriter flowWriter,
                        FileSystemService fileSystem,
                        PropertyResolver propertyResolver,
                        ReferenceResolver referenceResolver,
                        CleanupService cleanupService) {
        this.log = log;
        this.flowReader = flowReader;
        this.flowWriter = flowWriter;
        this.fileSystem = fileSystem;
        this.propertyResolver = propertyResolver;
        this.referenceResolver = referenceResolver;
        this.cleanupService = cleanupService;
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
            FlowFile flow = flowReader.read(flowPath, config);
            log.info("Processing flow: " + flow.getFlowName());

            boolean flowHasErrors = processFlow(flow, config, collectedErrors);

            if (!flowHasErrors) {
                flowWriter.write(flow);
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
     * @return true if any errors were collected during processing of this flow
     */
    private boolean processFlow(FlowFile flow, PluginConfig config,
                                 List<BuildException> collectedErrors)
            throws IOException {

        int errorsBefore = collectedErrors.size();

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
                        buildFromProcessor(flow, processor, mapping);
                    } catch (BuildException e) {
                        collectedErrors.add(e);
                        log.debug(String.format(
                                "Skipping processor '%s' (id: %s, group: '%s', groupId: %s, " +
                                        "flow: '%s', flowPath: '%s') due to error: %s",
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

        return collectedErrors.size() > errorsBefore;
    }

    /**
     * Restores a single property of a single processor from its extracted file:
     * - resolves the property by name or regex
     * - if property is a reference: reads file content and writes it back to property
     * - if property is an inline value: checks for conflict with existing extracted file
     * - if property is empty: skips with a warning
     *
     * @throws BuildException if the referenced file does not exist,
     *                        or an inline value conflicts with an existing extracted file
     */
    private void buildFromProcessor(FlowFile flow,
                                     Processor processor,
                                     PropertyMapping mapping)
            throws BuildException, IOException {

        Optional<ProcessorProperty> propertyOpt;
        try {
            propertyOpt = propertyResolver.resolve(processor, mapping);
        } catch (ExtractException e) {
            throw new BuildException(e.getMessage(), e);
        }

        if (propertyOpt.isEmpty()) {
            return;
        }

        ProcessorProperty property = propertyOpt.get();

        if (property.isEmpty()) {
            log.warn(String.format(
                    "Property '%s' of processor '%s' (id: %s, group: '%s', groupId: %s, flow: '%s') " +
                            "is empty or null. Skipping.",
                    property.getName(),
                    processor.getName(),
                    processor.getIdentifier(),
                    processor.getParentGroup().getName(),
                    processor.getParentGroup().getIdentifier(),
                    flow.getFlowName()));
            return;
        }

        if (property.isReference()) {
            Path filePath = referenceResolver.resolve(flow, processor, property);
            String content = fileSystem.readText(filePath);
            property.setValue(content);
            log.info(String.format("Restored property '%s' of processor '%s' from %s",
                    property.getName(), processor.getName(), filePath));
        } else {
            referenceResolver.checkConflict(flow, processor, property,
                    mapping.getTargetFilename());
            throw new BuildException(String.format(
                    "Property '%s' of processor '%s' (id: %s, group: '%s', groupId: %s, flow: '%s') " +
                            "has an inline value. Extract must be run before Build.",
                    property.getName(),
                    processor.getName(),
                    processor.getIdentifier(),
                    processor.getParentGroup().getName(),
                    processor.getParentGroup().getIdentifier(),
                    flow.getFlowName()));
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
