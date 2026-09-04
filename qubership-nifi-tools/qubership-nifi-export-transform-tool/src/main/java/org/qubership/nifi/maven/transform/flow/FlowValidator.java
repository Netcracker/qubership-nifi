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
 * Checks that all target processors map to unique export paths within the flow,
 * and that regex property mappings match exactly one property per processor.
 *
 * Characters not allowed in file system paths are not rejected here: processor
 * and group names are passed through PathSegmentEncoder when the export path is
 * built, so any name is accepted. The uniqueness check runs on the encoded paths,
 * so it also catches names that clash only after replacement (for example a group
 * named "Filter status>0" and a group named "Filter status_gt_0").
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

        // Paths are intentionally checked across all processor types, not just within a single type.
        // Two different types can map to the same target file name (for example, query.sql for the
        // SQL Query property), so two processors that share a path would write to the same file.
        // Requiring unique paths guarantees that each extracted directory belongs to a single processor
        // and avoids mixing data from two sources, which would confuse users.
        // The key is the encoded relative path string (Processor.getRelativePath()), the same path
        // used to lay out the extracted files, so it also reports clashes that appear only after
        // replacing unsafe characters (a group named "a>b" and a group named "a_gt_b"), as well as a
        // group literally named "a / b" clashing with the nested pair "a" then "b". Forward slashes
        // are used so the comparison is identical on every operating system.
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
