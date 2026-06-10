package org.qubership.nifi.maven.transform.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root model of the plugin configuration YAML file.
 * Created by ConfigLoader after parsing the YAML structure.
 */
public class PluginConfig {

    private final List<ProcessorTypeConfig> processorTypes;
    private final Set<String> processorTypeFqns;

    /**
     * Constructs a PluginConfig from the given list of processor type configurations.
     *
     * @param processorTypesValue list of processor type configurations parsed from the YAML file
     */
    public PluginConfig(final List<ProcessorTypeConfig> processorTypesValue) {
        this.processorTypes = Collections.unmodifiableList(processorTypesValue);
        this.processorTypeFqns = processorTypesValue.stream()
                .map(ProcessorTypeConfig::getProcessorTypeFqn)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns all processor type configurations defined in the config file.
     *
     * @return unmodifiable list of processor type configs
     */
    public List<ProcessorTypeConfig> getProcessorTypes() {
        return processorTypes;
    }

    /**
     * Returns the set of processor type FQNs defined in the config file.
     * Pre-computed once at construction time.
     *
     * @return unmodifiable set of processor type FQNs
     */
    public Set<String> getProcessorTypeFqns() {
        return processorTypeFqns;
    }

    /**
     * Finds a processor type configuration by its fully qualified class name.
     *
     * @param typeFqn fully qualified processor type name
     * @return matching config, or empty if not found
     */
    public Optional<ProcessorTypeConfig> findByType(String typeFqn) {
        return processorTypes.stream()
                .filter(c -> c.getProcessorTypeFqn().equals(typeFqn))
                .findFirst();
    }
}
