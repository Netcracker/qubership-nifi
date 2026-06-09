package org.qubership.nifi.maven.transform.flow;

import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates the structural integrity of a flow before the Extract operation.
 *
 * Checks that all target processors have unique full paths within the flow,
 * that all path segments contain only characters valid in file system paths,
 * and that regex property mappings match exactly one property per processor.
 */
public class FlowValidator {

    private static final Pattern INVALID_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");

    /**
     * Validates all processors of configured types in the given flow.
     * All errors across all configured types are collected and returned,
     * so the caller sees every problem in a single run.
     *
     * @param flow   flow to validate, must contain a pre-built processorsByType map
     * @param config plugin config defining which processor types to handle
     * @return list of validation error messages; empty if the flow is valid
     */
    public List<String> validate(FlowFile flow, PluginConfig config) {
        List<String> errors = new ArrayList<>();

        for (var typeConfig : config.getProcessorTypes()) {
            String typeFqn = typeConfig.getProcessorTypeFqn();
            List<Processor> processors = flow.getProcessorsByType(typeFqn);
            collectDuplicatePaths(processors, typeFqn, errors);
        }

        collectInvalidSegments(flow, config, errors);
        collectAmbiguousRegexMappings(flow, config, errors);

        return errors;
    }

    private void collectDuplicatePaths(List<Processor> processors, String typeFqn,
                                       List<String> errors) {
        Map<String, String> seenPaths = new HashMap<>();

        for (Processor processor : processors) {
            String fullPath = processor.getFullPath();
            String existingId = seenPaths.putIfAbsent(fullPath, processor.getIdentifier());

            if (existingId != null) {
                errors.add(String.format(
                        "Duplicate processor path '%s' for type '%s': "
                                + "processor '%s' and processor '%s' produce the same path. "
                                + "Processors of the same type must have unique paths "
                                + "(group segments + processor name) within the flow, "
                                + "because the path is used as the directory structure during Extract.",
                        fullPath, typeFqn, existingId, processor.getIdentifier()));
            }
        }
    }

    private void collectInvalidSegments(FlowFile flow, PluginConfig config,
                                        List<String> errors) {
        validateSegment(flow.getFlowName(), "flow name", errors);

        for (var typeConfig : config.getProcessorTypes()) {
            for (Processor processor : flow.getProcessorsByType(typeConfig.getProcessorTypeFqn())) {
                for (String segment : processor.getParentGroup().getPathSegments()) {
                    validateSegment(segment, "process group name", errors);
                }
                validateSegment(processor.getName(), "processor name", errors);
            }
        }
    }

    private void validateSegment(String segment, String segmentType, List<String> errors) {
        if (INVALID_CHARS.matcher(segment).find()) {
            errors.add(String.format(
                    "Invalid characters in %s '%s'. "
                            + "The following characters are not allowed in file system paths: "
                            + "/ \\ : * ? \" < > |",
                    segmentType, segment));
        }
    }

    private void collectAmbiguousRegexMappings(FlowFile flow, PluginConfig config,
                                               List<String> errors) {
        for (var typeConfig : config.getProcessorTypes()) {
            for (Processor processor : flow.getProcessorsByType(typeConfig.getProcessorTypeFqn())) {
                for (PropertyMapping mapping : typeConfig.getPropertyMappings()) {
                    if (mapping.isRegex()) {
                        List<ProcessorProperty> matches = processor.findPropertiesByRegex(
                                mapping.getCompiledPattern());
                        if (matches.size() > 1) {
                            List<String> matchedNames = matches.stream()
                                    .map(ProcessorProperty::getName)
                                    .toList();
                            errors.add(String.format(
                                    "Regex '%s' matches multiple properties %s in processor '%s'. "
                                            + "The pattern must match exactly one property.",
                                    mapping.getPropertyNameOrRegex(), matchedNames,
                                    processor.getName()));
                        }
                    }
                }
            }
        }
    }
}
