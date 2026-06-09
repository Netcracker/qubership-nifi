package org.qubership.nifi.maven.transform.extract;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a processor property according to a mapping from the config.
 */
public class PropertyResolver {

    private final Log log;


    /**
     * Constructor for class PropertyResolver.
     *
     * @param logger maven logger
     */
    public PropertyResolver(final Log logger) {
        this.log = logger;
    }

    /**
     * Finds a processor property according to the given mapping.
     * Uses exact name matching or regex depending on the mapping type.
     *
     * @param processor processor to search in
     * @param mapping   property mapping from config (name or regex to targetFilename)
     * @return found property, or empty if none matched
     */
    public Optional<ProcessorProperty> resolve(Processor processor, PropertyMapping mapping) {

        if (mapping.isRegex()) {
            return resolveByRegex(processor, mapping);
        } else {
            return resolveByName(processor, mapping);
        }
    }

    /**
     * Resolves a processor property by exact name match.
     *
     * @param processor the processor to search in
     * @param mapping   the property mapping whose name is used for the lookup
     * @return the matching property, or empty if no property with that name exists
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
     * Resolves a processor property by matching its name against a compiled regex pattern.
     *
     * <p>All properties of the processor are tested against the pattern. If no property
     * matches, a warning is logged and an empty Optional is returned. Ambiguous patterns
     * that match multiple properties are caught upfront by FlowValidator, so by the time
     * this method is called there is always at most one match.
     *
     * @param processor the processor to search in
     * @param mapping   the property mapping whose compiled pattern is used for matching
     * @return the single matching property, or empty if no property matched
     */
    private Optional<ProcessorProperty> resolveByRegex(Processor processor,
                                                       PropertyMapping mapping) {

        List<ProcessorProperty> matches = processor.findPropertiesByRegex(
                mapping.getCompiledPattern());

        if (matches.isEmpty()) {
            log.warn(String.format(
                    "No property matching regex '%s' found in processor '%s'. Skipping.",
                    mapping.getPropertyNameOrRegex(), processor.getName()));
            return Optional.empty();
        }

        return Optional.of(matches.get(0));
    }
}
