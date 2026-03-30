package org.qubership.cloud.nifi.config.common;

import java.util.Set;

/**
 * Configuration record for {@link BasePropertiesManager}.
 * <p>
 * Encapsulates all parameters required to initialize a {@link BasePropertiesManager} instance,
 * including resource names for default templates, configuration file paths,
 * property prefix for filtering, readonly property names, and the properties provider.
 *
 * @param defaultLogbackXmlResourceName default logback XML template resource name
 * @param defaultPropertiesResourceName default properties template resource name
 * @param internalPropertiesResourceName internal (unchangeable) properties resource name
 * @param internalPropertiesCommentsResourceName internal properties comments resource name
 * @param configFilePath configuration file output directory path
 * @param configFileName configuration file name
 * @param propertyPrefix prefix used to filter relevant properties from the source
 * @param readonlyPropertyNames set of property names that must not be configured from the source
 * @param propertiesProvider provider for retrieving configuration property values
 */
public record BasePropertiesManagerConfig(
        String defaultLogbackXmlResourceName,
        String defaultPropertiesResourceName,
        String internalPropertiesResourceName,
        String internalPropertiesCommentsResourceName,
        String configFilePath,
        String configFileName,
        String propertyPrefix,
        Set<String> readonlyPropertyNames,
        PropertiesProvider propertiesProvider
) {
}
