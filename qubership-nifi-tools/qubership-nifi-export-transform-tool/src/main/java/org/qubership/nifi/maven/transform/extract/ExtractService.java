package org.qubership.nifi.maven.transform.extract;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowScanner;
import org.qubership.nifi.maven.transform.flow.FlowValidator;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Path;
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
    private final FlowScanner flowScanner;
    private final FlowValidator flowValidator;
    private final FileSystemService fileSystem;
    private final PropertyResolver propertyResolver;
    private final ReferenceBuilder referenceBuilder;

    public ExtractService(Log log,
                          FlowReader flowReader,
                          FlowWriter flowWriter,
                          FlowScanner flowScanner,
                          FlowValidator flowValidator,
                          FileSystemService fileSystem,
                          PropertyResolver propertyResolver,
                          ReferenceBuilder referenceBuilder) {
        this.log = log;
        this.flowReader = flowReader;
        this.flowWriter = flowWriter;
        this.flowScanner = flowScanner;
        this.flowValidator = flowValidator;
        this.fileSystem = fileSystem;
        this.propertyResolver = propertyResolver;
        this.referenceBuilder = referenceBuilder;
    }

    /**
     * Runs Extract on all flow files found in the given directory.
     *
     * @param config    parsed plugin config
     * @param exportDir root directory containing exported NiFi flow files
     * @throws ExtractException if a fatal error occurs (ambiguous regex,
     *                          duplicate processor or group names, invalid path characters, etc.)
     * @throws IOException      if a file cannot be read or written
     */
    public void extract(PluginConfig config, Path exportDir)
            throws ExtractException, IOException {

        log.info("Starting Extract from " + exportDir.toAbsolutePath());

        List<FlowFile> flows = flowReader.readAll(exportDir);
        log.info("Found " + flows.size() + " flow file(s) to process");

        for (FlowFile flow : flows) {
            log.info("Processing flow: " + flow.getFlowName());
            flowValidator.validateNamesUniqueness(flow);
            processFlow(flow, config);
            flowWriter.write(flow);
        }
    }

    private void processFlow(FlowFile flow, PluginConfig config)
            throws ExtractException, IOException {

        for (ProcessorTypeConfig typeConfig : config.getProcessorTypes()) {
            List<Processor> processors = flowScanner.findProcessorsByType(
                    flow, typeConfig.getProcessorTypeFqn());

            if (processors.isEmpty()) {
                log.debug("No processors of type '" + typeConfig.getProcessorTypeFqn()
                        + "' found in flow '" + flow.getFlowName() + "'");
                continue;
            }

            for (Processor processor : processors) {
                for (PropertyMapping mapping : typeConfig.getPropertyMappings()) {
                    extractFromProcessor(flow, processor, mapping);
                }
            }
        }
    }

    /**
     * Extracts a single property from a single processor:
     * - resolves the property by name or regex
     * - skips with a warning if the value is already a reference or is empty
     * - writes the value to the target file
     * - replaces the property value with a @relative_path reference
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
}
