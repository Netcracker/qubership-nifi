package org.qubership.cloud.nifi.config;

import org.qubership.cloud.nifi.config.common.BasePropertiesManager;
import org.qubership.cloud.nifi.config.common.BasePropertiesManagerConfig;
import org.qubership.cloud.nifi.config.xml.BaseXmlConfigValidator;
import org.qubership.cloud.nifi.config.xml.XmlConfigValidatorConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * Spring configuration class that produces a {@link BasePropertiesManager} bean
 * configured with NiFi Registry resource paths and the Consul properties provider.
 */
@Configuration
public class ConsulConfiguration {
    private static final Set<String> READ_ONLY_NIFI_PROPERTIES = new HashSet<>();

    static {
        READ_ONLY_NIFI_PROPERTIES.add("nifi.security.identity.mapping.pattern.dn");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.security.identity.mapping.value.dn");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.security.identity.mapping.transform.dn");
    }

    private static final Set<String> CUSTOM_PROPERTIES = new HashSet<>();

    static {
        CUSTOM_PROPERTIES.add("nifi.http-auth-proxying-disabled-schemes");
        CUSTOM_PROPERTIES.add("nifi.http-auth-tunneling-disabled-schemes");
        CUSTOM_PROPERTIES.add("nifi.cluster.base-node-count");
        CUSTOM_PROPERTIES.add("nifi.cluster.start-mode");
        CUSTOM_PROPERTIES.add("nifi.nifi-registry.nar-provider-enabled");
        CUSTOM_PROPERTIES.add("nifi.conf.clean-db-repository");
        CUSTOM_PROPERTIES.add("nifi.conf.clean-configuration");
        CUSTOM_PROPERTIES.add("nifi.extensions.retry.attempts");
        CUSTOM_PROPERTIES.add("nifi.extensions.retry.delay");

    }

    /**
     * Creates a {@link BasePropertiesManager} bean configured with
     * NiFi resource paths and the given Consul properties provider.
     *
     * @param defaultLogbackFile default logback XML template resource name
     * @param defaultPropertiesFile default properties template resource name
     * @param internalPropertiesFile internal (unchangeable) properties resource name
     * @param internalPropertiesCommentsFile internal properties comments resource name
     * @param defaultCustomPropertiesFile default custom (unrelated to Apache NiFi) properties resource name
     * @param path configuration file output path
     * @param propertiesProvider the Consul properties provider
     * @return configured {@link BasePropertiesManager} instance
     */
    @Bean
    public BasePropertiesManager basePropertiesManager(
            @Value("${nifi.config.logback.default:logback-template.xml}") String defaultLogbackFile,
            @Value("${nifi.config.properties.default:nifi_default.properties}")
            String defaultPropertiesFile,
            @Value("${nifi.config.properties.internal:nifi_internal.properties}")
            String internalPropertiesFile,
            @Value("${nifi.config.properties.comments:nifi_internal_comments.properties}")
            String internalPropertiesCommentsFile,
            @Value("${nifi.config.properties.custom:custom.properties}")
            String defaultCustomPropertiesFile,
            @Value("${config.file.path}") String path,
            ConsulPropertiesProvider propertiesProvider) {
        return new BasePropertiesManager(new BasePropertiesManagerConfig(
                defaultLogbackFile,
                defaultPropertiesFile,
                internalPropertiesFile,
                internalPropertiesCommentsFile,
                path,
                "nifi.properties",
                "nifi",
                READ_ONLY_NIFI_PROPERTIES,
                defaultCustomPropertiesFile,
                CUSTOM_PROPERTIES,
                propertiesProvider
        ));
    }

    /**
     * Creates a {@link BaseXmlConfigValidator} bean configured with NiFi configuration paths.
     * @param path configuration file output path
     * @param mainConfigDirectoryPath primary configuration directory path
     * @param restoreDirectoryPath restore configuration directory path
     * @return BaseXmlConfigValidator instance
     */
    @Bean
    public BaseXmlConfigValidator baseXmlConfigValidator(@Value("${config.file.path}") String path,
                                                         @Value("${config.main.path}") String mainConfigDirectoryPath,
                                                         @Value("${config.restore.path}") String restoreDirectoryPath) {
        return new BaseXmlConfigValidator(
                new XmlConfigValidatorConfig(path, mainConfigDirectoryPath, restoreDirectoryPath));
    }
}
