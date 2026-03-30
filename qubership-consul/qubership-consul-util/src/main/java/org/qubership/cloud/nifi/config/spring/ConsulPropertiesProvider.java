package org.qubership.cloud.nifi.config.spring;

import org.qubership.cloud.nifi.config.common.PropertiesProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.consul.config.ConsulPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Consul properties provider.
 */
@Component
public class ConsulPropertiesProvider
        implements PropertiesProvider {

    private ConfigurableEnvironment env;
    private Environment appEnv;
    /**
     * Default constructor for Spring.
     * @param configEnv ConfigurableEnvironment instance to use
     * @param applicationEnv application Environment instance to use
     */
    @Autowired
    public ConsulPropertiesProvider(final ConfigurableEnvironment configEnv,
                                    final Environment applicationEnv) {
        this.env = configEnv;
        this.appEnv = applicationEnv;
    }

    /**
     * Gets all available property names from Consul.
     * @return set of property names
     */
    @Override
    public Set<String> getAllPropertyNamesFromSource() {
        MutablePropertySources sources = env.getPropertySources();
        Set<String> allPropertyNames = new HashSet<>();
        for (PropertySource<?> src1 : sources) {
            // get properties for ConsulPropertySource
            if (ConsulPropertySource.class.isAssignableFrom(src1.getClass())) {
                String[] allNames = ((ConsulPropertySource) src1).getPropertyNames();
                //fetch only properties starting with logger.* and nifi.*
                for (String name : allNames) {
                    String lowercaseName = name.toLowerCase();
                    if (lowercaseName.startsWith("logger.") || lowercaseName.startsWith("nifi.")) {
                        allPropertyNames.add(name);
                    }
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
        return appEnv.getProperty(propertyName);
    }
}
