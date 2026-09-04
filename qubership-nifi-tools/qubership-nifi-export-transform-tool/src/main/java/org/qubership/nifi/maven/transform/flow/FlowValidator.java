package org.qubership.nifi.maven.transform.flow;

import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the structural integrity of a flow before the Extract operation.
 *
 * Checks that all target processors map to unique export paths, and that regex
 * property mappings match exactly one property per processor.
 *
 * Names are not restricted here: PathSegmentEncoder replaces unsafe characters
 * when the export path is built, so the uniqueness check runs on the encoded
 * path and also catches names that only clash after replacement.
 */
public class FlowValidator {

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

        // Checked across all processor types, not just within one: two types can map to the same
        // target filename, so two processors sharing a path would write to the same file.
        // The key is the encoded relative path (Processor.getRelativePath(), "/"-separated), the
        // same path used on disk, so this also catches names that only clash after encoding.
        Map<String, Processor> seenPaths = new HashMap<>();

        for (var typeConfig : config.getProcessorTypes()) {
            List<Processor> processors = flow.getProcessorsByType(typeConfig.getProcessorTypeFqn());
            collectDuplicatePaths(processors, errors, seenPaths);
        }

        collectAmbiguousRegexMappings(flow, config, errors);

        return errors;
    }

    private void collectDuplicatePaths(List<Processor> processors,
                                       List<String> errors, Map<String, Processor> seenPaths) {

        for (Processor processor : processors) {
            String exportPath = processor.getRelativePath().toString().replace("\\", "/");
            Processor existing = seenPaths.putIfAbsent(exportPath, processor);

            if (existing != null) {
                errors.add(String.format(
                        "Duplicate processor path '%s': processor '%s' (%s) and processor '%s' (%s) "
                                + "map to the same export path after replacing characters not allowed "
                                + "in file system paths. Processors must map to unique paths within "
                                + "the flow, since the path determines the directory structure "
                                + "during Extract.",
                        exportPath,
                        existing.getFullPath(), existing.getIdentifier(),
                        processor.getFullPath(), processor.getIdentifier()));
            }
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
