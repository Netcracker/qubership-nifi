package org.qubership.nifi.maven.transform.config;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for a single processor type:
 * the fully qualified class name and a list of property mappings.
 */
public class ProcessorTypeConfig {

    private final String processorTypeFqn;
    private final List<PropertyMapping> propertyMappings;

    /**
     * Constructs a ProcessorTypeConfig for the given processor type.
     *
     * @param processorTypeFqn fully qualified class name of the processor type
     * @param propertyMappings list of property mappings for this type
     */
    public ProcessorTypeConfig(final String processorTypeFqnValue,
                               final List<PropertyMapping> propertyMappingsValue) {
        this.processorTypeFqn = processorTypeFqnValue;
        this.propertyMappings = Collections.unmodifiableList(propertyMappingsValue);
    }

    /**
     * Returns the fully qualified class name of the processor type.
     *
     * @return fully qualified type name,
     *         e.g. "org.apache.nifi.processors.standard.ExecuteSQL"
     */
    public String getProcessorTypeFqn() {
        return processorTypeFqn;
    }

    /**
     * Returns the list of property mappings defined for this processor type.
     *
     * @return unmodifiable list of property mappings
     */
    public List<PropertyMapping> getPropertyMappings() {
        return propertyMappings;
    }
}
