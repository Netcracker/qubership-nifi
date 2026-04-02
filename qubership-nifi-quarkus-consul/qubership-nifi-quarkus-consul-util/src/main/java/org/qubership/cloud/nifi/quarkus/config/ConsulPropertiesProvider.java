package org.qubership.cloud.nifi.quarkus.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.qubership.cloud.nifi.config.common.PropertiesProvider;

import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class ConsulPropertiesProvider implements PropertiesProvider {

    @Inject
    private Config config;

    /**
     * Gets all available property names from source.
     * @return set of property names
     */
    @Override
    public Set<String> getAllPropertyNamesFromSource() {
        Set<String> allPropertyNames = new HashSet<>();

        // Get all config sources from MicroProfile Config
        for (ConfigSource configSource : config.getConfigSources()) {
            Set<String> allNames = configSource.getPropertyNames();
            for (String name : allNames){
                if (name.toLowerCase().startsWith("logger.") || name.toLowerCase().startsWith("nifi.")) {
                    allPropertyNames.add(name);
                }
            }
        }
        return allPropertyNames;
    }

    /**
     * Get property value with the specified name.
     * @param propertyName property name to get
     * @return property value
     */
    @Override
    public String getPropertyValue(String propertyName) {
        return config.getOptionalValue(propertyName, String.class).orElse(null);
    }
}
