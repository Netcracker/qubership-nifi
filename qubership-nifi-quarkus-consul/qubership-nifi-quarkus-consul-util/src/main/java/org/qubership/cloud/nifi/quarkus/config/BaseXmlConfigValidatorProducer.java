package org.qubership.cloud.nifi.quarkus.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.nifi.config.common.PropertiesProvider;
import org.qubership.cloud.nifi.config.xml.BaseXmlConfigValidator;
import org.qubership.cloud.nifi.config.xml.XmlConfigValidatorConfig;

@ApplicationScoped
public class BaseXmlConfigValidatorProducer {
    @Inject
    @ConfigProperty(name = "nifi.config.path")
    private String defaultPath;

    @Inject
    @ConfigProperty(name = "nifi.config.main.path")
    private String defaultMainConfigDirectoryPath;

    @Inject
    @ConfigProperty(name = "nifi.config.restore.path")
    private String defaultRestoreDirectoryPath;

    /**
     * Produces a {@link BaseXmlConfigValidator} instance configured with
     * NiFi resource paths and the given properties provider.
     *
     * @param provider the properties provider for retrieving configuration values
     * @return configured {@link BaseXmlConfigValidator} instance
     */
    @Produces
    public BaseXmlConfigValidator baseXmlConfigValidator(PropertiesProvider provider) {
        return new BaseXmlConfigValidator(new XmlConfigValidatorConfig(
                defaultPath,
                defaultMainConfigDirectoryPath,
                defaultRestoreDirectoryPath
        ));
    }
}
