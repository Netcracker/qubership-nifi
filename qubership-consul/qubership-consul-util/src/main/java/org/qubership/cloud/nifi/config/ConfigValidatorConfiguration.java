package org.qubership.cloud.nifi.config;

import org.qubership.cloud.nifi.config.xml.BaseXmlConfigValidator;
import org.qubership.cloud.nifi.config.xml.XmlConfigValidatorConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class that produces a {@link BaseXmlConfigValidator} bean
 * configured with NiFi resource paths and the Consul properties provider.
 */
@Configuration
public class ConfigValidatorConfiguration {
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
