package org.qubership.nifi.maven.transform.flow;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates the structural integrity of a flow before the Extract operation.
 *
 * Checks that all target processors map to unique export paths, and that regex
 * property mappings match exactly one property per processor. Two paths are compared
 * case-insensitively, since Windows and the macOS default file system treat them as
 * the same directory regardless of the platform Extract actually runs on.
 *
 * A processor whose name collides with another's is disambiguated with an identifier
 * suffix, not rejected; each such collision is logged. An error is reported only if a
 * processor's final export path still matches another's: either two identifier suffixes
 * collide, or a processor's plain name already equals another processor's suffixed name.
 */
public class FlowValidator {

    private final Log log;
    private final DuplicatePathResolver duplicatePathResolver = new DuplicatePathResolver();

    /**
     * Constructor for class FlowValidator.
     *
     * @param logger maven logger
     */
    public FlowValidator(final Log logger) {
        this.log = logger;
    }

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

        List<Processor> allProcessors = new ArrayList<>();
        for (var typeConfig : config.getProcessorTypes()) {
            allProcessors.addAll(flow.getProcessorsByType(typeConfig.getProcessorTypeFqn()));
        }

        List<List<Processor>> collisions = duplicatePathResolver.disambiguate(allProcessors);
        logDisambiguatedCollisions(collisions);
        Map<String, Processor> seenPaths = new HashMap<>();
        collectDuplicatePaths(allProcessors, errors, seenPaths);

        collectAmbiguousRegexMappings(flow, config, errors);

        return errors;
    }

    private void logDisambiguatedCollisions(List<List<Processor>> collisions) {
        for (List<Processor> group : collisions) {
            List<String> disambiguatedPaths = group.stream()
                    .map(p -> p.getFullPath() + " (" + p.getIdentifier() + ") -> " + p.getRelativePath())
                    .toList();
            log.info(String.format(
                    "Processor name collision: %d processors share the same export path; "
                            + "each was given a distinct directory using its identifier: %s. "
                            + "A processor's directory depends on which other processors collide "
                            + "with it, so a directory from an earlier Extract run may now be unused.",
                    group.size(), disambiguatedPaths));
        }
    }

    private void collectDuplicatePaths(List<Processor> processors,
                                       List<String> errors, Map<String, Processor> seenPaths) {

        for (Processor processor : processors) {
            String exportPath = processor.getRelativePath().toString().replace("\\", "/");
            Processor existing = seenPaths.putIfAbsent(exportPath.toLowerCase(Locale.ROOT), processor);

            if (existing != null) {
                String existingPath = existing.getRelativePath().toString().replace("\\", "/");
                errors.add(String.format(
                        "Duplicate processor path: processor '%s' (%s) resolves to '%s' and processor "
                                + "'%s' (%s) resolves to '%s'. The two are the same path on a file system "
                                + "that ignores case, such as Windows or the macOS default. Rename one of "
                                + "the processors, or move one into a process group with a different name.",
                        existing.getFullPath(), existing.getIdentifier(), existingPath,
                        processor.getFullPath(), processor.getIdentifier(), exportPath));
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
