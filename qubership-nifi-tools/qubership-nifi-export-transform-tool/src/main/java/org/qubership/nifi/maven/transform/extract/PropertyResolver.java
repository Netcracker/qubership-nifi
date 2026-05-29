package org.qubership.nifi.maven.transform.extract;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a processor property according to a mapping from the config.
 */
public class PropertyResolver {

    private final Log log;

    public PropertyResolver(Log log) {
        this.log = log;
    }

    /**
     * Finds a processor property according to the given mapping.
     * Uses exact name matching or regex depending on the mapping type.
     *
     * @param processor processor to search in
     * @param mapping   property mapping from config (name or regex to targetFilename)
     * @return found property, or empty if none matched
     * @throws ExtractException if a regex matches more than one property
     */
    public Optional<ProcessorProperty> resolve(Processor processor, PropertyMapping mapping)
            throws ExtractException {

        if (mapping.isRegex()) {
            return resolveByRegex(processor, mapping);
        } else {
            return resolveByName(processor, mapping);
        }
    }

    /**
     * Resolves a property by exact name.
     * Logs a warning if the property is not set in the processor.
     */
    private Optional<ProcessorProperty> resolveByName(Processor processor,
                                                      PropertyMapping mapping) {
        Optional<ProcessorProperty> result = processor.findProperty(
                mapping.getPropertyNameOrRegex());

        if (result.isEmpty()) {
            log.warn(String.format(
                    "Property '%s' is not set in processor '%s'. Skipping.",
                    mapping.getPropertyNameOrRegex(), processor.getName()));
        }

        return result;
    }

    /**
     * Resolves a property by regex pattern.
     * Logs a warning if no property matches.
     * Throws ExtractException if more than one property matches.
     */
    private Optional<ProcessorProperty> resolveByRegex(Processor processor,
                                                       PropertyMapping mapping)
            throws ExtractException {

        List<ProcessorProperty> matches = processor.findPropertiesByRegex(
                mapping.getCompiledPattern());

        if (matches.isEmpty()) {
            log.warn(String.format(
                    "No property matching regex '%s' found in processor '%s'. Skipping.",
                    mapping.getPropertyNameOrRegex(), processor.getName()));
            return Optional.empty();
        }

        if (matches.size() > 1) {
            List<String> matchedNames = matches.stream()
                    .map(ProcessorProperty::getName)
                    .toList();
            throw new ExtractException(String.format(
                    "Regex '%s' matches multiple properties %s in processor '%s'. " +
                            "The pattern must match exactly one property.",
                    mapping.getPropertyNameOrRegex(), matchedNames, processor.getName()));
        }

        return Optional.of(matches.get(0));
    }
}
