package org.qubership.nifi.maven.transform.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Root model of the plugin configuration YAML file.
 * Created by ConfigLoader after parsing the YAML structure.
 */
public class PluginConfig {

    private final List<ProcessorTypeConfig> processorTypes;

    public PluginConfig(List<ProcessorTypeConfig> processorTypes) {
        this.processorTypes = Collections.unmodifiableList(processorTypes);
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
