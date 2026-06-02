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

    public ExtractService(Log log,
                          FlowReader flowReader,
                          FlowWriter flowWriter,
                          FlowValidator flowValidator,
                          FileSystemService fileSystem,
                          PropertyResolver propertyResolver,
                          ReferenceBuilder referenceBuilder) {
        this.log = log;
        this.flowReader = flowReader;
        this.flowWriter = flowWriter;
        this.flowValidator = flowValidator;
        this.fileSystem = fileSystem;
        this.propertyResolver = propertyResolver;
        this.referenceBuilder = referenceBuilder;
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

        List<ExtractException> collectedErrors = new ArrayList<>();

        for (Path flowPath : flowPaths) {
            FlowFile flow = flowReader.read(flowPath, config);
            log.info("Processing flow: " + flow.getFlowName());

            try {
                flowValidator.validateNamesUniqueness(flow, config);
            } catch (ExtractException e) {
                collectedErrors.add(e);
                log.debug("Skipping flow '" + flow.getFlowName()
                        + "' due to validation error: " + e.getMessage());
                continue;
            }

            boolean flowHasErrors = processFlow(flow, config, collectedErrors);

            if (!flowHasErrors) {
                flowWriter.write(flow);
            }
        }

        reportErrors(collectedErrors);
    }


    /**
     * Processes a single flow file: for each processor type defined in the config,
     * extracts properties from all matching processors.
     * ExtractExceptions are collected into collectedErrors rather than thrown.
     *
     * @return true if any errors were collected during processing of this flow
     */
    private boolean processFlow(FlowFile flow, PluginConfig config,
                                List<ExtractException> collectedErrors)
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
                        extractFromProcessor(flow, processor, mapping);
                    } catch (ExtractException e) {
                        collectedErrors.add(e);
                        log.debug("Skipping processor '" + processor.getName()
                                + "' due to error: " + e.getMessage());
                    }
                }
            }
        }

        return collectedErrors.size() > errorsBefore;
    }

    /**
     * Extracts a single property from a single processor:
     * - resolves the property by name or regex
     * - skips with a warning if the value is already a reference or is empty
     * - writes the value to the target file
     * - replaces the property value with a @relative/path reference
     *
     * @throws ExtractException if the property path contains invalid characters
     *                          or a regex matches multiple properties
     */
    private void extractFromProcessor(FlowFile flow,
                                      Processor processor,
                                      PropertyMapping mapping)
            throws ExtractException, IOException {

        Optional<ProcessorProperty> propertyOpt = propertyResolver.resolve(processor, mapping);

        if (propertyOpt.isEmpty()) {
            return;
        }

        ProcessorProperty property = propertyOpt.get();

        if (property.isReference()) {
            log.warn(String.format(
                    "Property '%s' of processor '%s' already contains a reference (%s). Skipping.",
                    property.getName(), processor.getName(), property.getValue()));
            return;
        }

        if (property.isEmpty()) {
            log.warn(String.format(
                    "Property '%s' of processor '%s' is empty or null. Skipping file creation.",
                    property.getName(), processor.getName()));
            return;
        }

        Path targetFile = referenceBuilder.buildAbsoluteFilePath(
                flow, processor, mapping.getTargetFilename());
        String reference = referenceBuilder.buildReference(
                flow, processor, mapping.getTargetFilename());

        fileSystem.createDirectories(targetFile.getParent());
        fileSystem.writeText(targetFile, property.getValue());
        property.setValue(reference);

        log.info(String.format("Extracted property '%s' of processor '%s' to %s",
                property.getName(), processor.getName(), targetFile));
    }

    /**
     * Logs all collected errors and throws a single ExtractException summarizing the failures.
     * Each error is logged individually so the user can see all problems at once.
     *
     * @param errors list of collected extraction errors
     * @throws ExtractException if the list is not empty
     */
    private void reportErrors(List<ExtractException> errors) throws ExtractException {
        if (errors.isEmpty()) {
            return;
        }

        log.error("Extract completed with " + errors.size() + " error(s):");
        for (int i = 0; i < errors.size(); i++) {
            log.error("  [" + (i + 1) + "] " + errors.get(i).getMessage());
        }

        throw new ExtractException(
                "Extract failed with " + errors.size() + " error(s). See log for details.");
    }
}
