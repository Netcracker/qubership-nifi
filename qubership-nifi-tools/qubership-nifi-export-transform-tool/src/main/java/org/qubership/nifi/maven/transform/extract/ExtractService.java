package org.qubership.nifi.maven.transform.extract;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowValidator;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Performs the Extract operation.
 *
 * Finds processors of configured types in exported NiFi flow JSON files,
 * extracts the values of specified properties into separate files,
 * and replaces the property values with file references of the form @path.
 */
public class ExtractService {

    private final Log log;
    private final FlowReader flowReader;
    private final FlowWriter flowWriter;
    private final FlowValidator flowValidator;
    private final FileSystemService fileSystem;
    private final PropertyResolver propertyResolver;
    private final ReferenceBuilder referenceBuilder;


    /**
     * Constructor for class ExtractService.
     *
     * @param logValue              Maven logger for info, warning, and debug messages
     * @param flowReaderValue       reads flow JSON files and builds the object model
     * @param flowWriterValue       writes the modified flow JSON back to disk
     * @param flowValidatorValue    validates processor path uniqueness before extraction
     * @param fileSystemValue       handles file and directory creation on disk
     * @param propertyResolverValue resolves processor properties by name or regex
     * @param referenceBuilderValue builds file paths and @reference strings
     */
    public ExtractService(final Log logValue,
                          final FlowReader flowReaderValue,
                          final FlowWriter flowWriterValue,
                          final FlowValidator flowValidatorValue,
                          final FileSystemService fileSystemValue,
                          final PropertyResolver propertyResolverValue,
                          final ReferenceBuilder referenceBuilderValue) {
        this.log = logValue;
        this.flowReader = flowReaderValue;
        this.flowWriter = flowWriterValue;
        this.flowValidator = flowValidatorValue;
        this.fileSystem = fileSystemValue;
        this.propertyResolver = propertyResolverValue;
        this.referenceBuilder = referenceBuilderValue;
    }

    /**
     * Runs Extract on all flow files found in the given directory.
     *
     * All ExtractExceptions are collected across all flows and processors
     * and reported together at the end. Processing continues even if errors occur.
     * Only IOException stops execution immediately.
     *
     * @param config    parsed plugin config
     * @param exportDir root directory containing exported NiFi flow files
     * @throws ExtractException if any extraction errors were collected
     * @throws IOException      if a file cannot be read or written
     */
    public void extract(PluginConfig config, Path exportDir)
            throws ExtractException, IOException {

        log.info("Starting Extract from " + exportDir.toAbsolutePath());

        List<Path> flowPaths = flowReader.findFlowPaths(exportDir);
        log.info("Found " + flowPaths.size() + " flow file(s) to process");

        List<String> collectedErrors = new ArrayList<>();

        for (Path flowPath : flowPaths) {
            java.util.Optional<FlowFile> flowOpt = flowReader.read(flowPath);
            if (flowOpt.isEmpty()) {
                log.debug("Skipping " + flowPath + ": no 'flowContents' found, not a NiFi flow file.");
                continue;
            }
            FlowFile flow = flowOpt.get();
            log.info("Processing flow: " + flow.getFlowName());

            List<String> conflicts = flowValidator.validate(flow, config);
            if (!conflicts.isEmpty()) {
                collectedErrors.addAll(conflicts);
                log.debug("Skipping flow '" + flow.getFlowName()
                        + "' due to " + conflicts.size() + " validation error(s).");
                continue;
            }

            int modified = processFlow(flow, config);
            if (modified > 0) {
                flowWriter.write(flow);
            } else {
                log.debug("Flow '" + flow.getFlowName() + "' had no properties to extract, skipping write.");
            }
        }

        reportErrors(collectedErrors);
    }


    /**
     * Processes a single flow file against all processor type configurations.
     *
     * If an IOException occurs mid-flow, all files written so far for this flow
     * are deleted before the exception is rethrown, preventing a partial on-disk
     * state where extracted files exist but the flow JSON was never updated.
     *
     * @param flow   the flow file to process
     * @param config the plugin config defining which processor types and properties to extract
     * @return number of properties actually extracted (property.setValue called)
     * @throws IOException if a property value file cannot be written
     */
    private int processFlow(FlowFile flow, PluginConfig config) throws IOException {
        int modified = 0;
        List<Path> writtenFiles = new ArrayList<>();

        try {
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
                        if (extractFromProcessor(flow, processor, mapping, writtenFiles)) {
                            modified++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            cleanupPartialFiles(writtenFiles, flow.getFlowName());
            throw e;
        }

        return modified;
    }

    /**
     * Extracts a single property from a single processor and writes its value to a file.
     * On success, adds the written file path to writtenFiles so the caller can clean up
     * partial state if a later IOException interrupts the flow.
     *
     * @param flow         the flow file containing the processor
     * @param processor    the processor whose property is being extracted
     * @param mapping      the property mapping from the config (name or regex → target filename)
     * @param writtenFiles accumulator of file paths written so far for this flow
     * @return true if the property value was extracted and replaced with a reference
     * @throws IOException if the target file or its parent directories cannot be created or written
     */
    private boolean extractFromProcessor(FlowFile flow,
                                         Processor processor,
                                         PropertyMapping mapping,
                                         List<Path> writtenFiles)
            throws IOException {

        Optional<ProcessorProperty> propertyOpt = propertyResolver.resolve(processor, mapping);

        if (propertyOpt.isEmpty()) {
            return false;
        }

        ProcessorProperty property = propertyOpt.get();

        if (property.isReference()) {
            log.warn(String.format(
                    "Property '%s' of processor '%s' already contains a reference (%s). Skipping.",
                    property.getName(), processor.getName(), property.getValue()));
            return false;
        }

        if (property.isEmpty()) {
            log.debug(String.format(
                    "Property '%s' of processor '%s' is empty or null. Skipping file creation.",
                    property.getName(), processor.getName()));
            return false;
        }

        Path targetFile = referenceBuilder.buildAbsoluteFilePath(
                flow, processor, mapping.getTargetFilename());
        String reference = referenceBuilder.buildReference(
                flow, processor, mapping.getTargetFilename());

        fileSystem.createDirectories(targetFile.getParent());
        fileSystem.writeText(targetFile, property.getValue());
        writtenFiles.add(targetFile);
        property.setValue(reference);

        log.info(String.format("Extracted property '%s' of processor '%s' to %s",
                property.getName(), processor.getName(), targetFile));
        return true;
    }

    /**
     * Deletes files written so far for a flow that failed with an IOException.
     * Cleanup failures are logged as warnings and do not suppress the original exception.
     *
     * @param writtenFiles files to delete
     * @param flowName     name of the flow, used in log messages
     */
    private void cleanupPartialFiles(List<Path> writtenFiles, String flowName) {
        if (writtenFiles.isEmpty()) {
            return;
        }
        log.warn("IOException during extraction of flow '" + flowName
                + "', cleaning up " + writtenFiles.size() + " partially written file(s).");
        for (Path file : writtenFiles) {
            try {
                fileSystem.deleteIfExists(file);
            } catch (IOException ex) {
                log.warn("Failed to clean up partial file '" + file + "': " + ex.getMessage());
            }
        }
    }

    /**
     * Logs all collected errors and throws a single ExtractException summarizing the failures.
     * Each error is logged individually so the user can see all problems at once.
     *
     * @param errors list of collected validation error messages
     * @throws ExtractException if the list is not empty
     */
    private void reportErrors(List<String> errors) throws ExtractException {
        if (errors.isEmpty()) {
            return;
        }

        log.error("Extract completed with " + errors.size() + " error(s):");
        for (int i = 0; i < errors.size(); i++) {
            log.error("  [" + (i + 1) + "] " + errors.get(i));
        }

        throw new ExtractException(
                "Extract failed with " + errors.size() + " error(s). See log for details.");
    }
}
