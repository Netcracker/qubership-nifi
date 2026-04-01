package org.qubership.cloud.nifi.quarkus.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.nifi.config.common.BasePropertiesManager;
import org.qubership.cloud.nifi.config.common.BasePropertiesManagerConfig;
import org.qubership.cloud.nifi.config.common.PropertiesProvider;

import java.util.HashSet;
import java.util.Set;

/**
 * CDI producer for {@link BasePropertiesManager}.
 * <p>
 * Reads NiFi configuration paths and resource names from MicroProfile Config
 * and produces an application-scoped {@link BasePropertiesManager} instance.
 */
@ApplicationScoped
public class BasePropertiesManagerProducer {
    @Inject
    @ConfigProperty(name = "nifi.config.path")
    private String path;

    @Inject
    @ConfigProperty(name = "nifi.config.logback.default", defaultValue = "logback-template.xml")
    private String defaultLogbackFile;

    @Inject
    @ConfigProperty(name = "nifi.config.properties.default", defaultValue = "nifi_default.properties")
    private String defaultPropertiesFile;

    @Inject
    @ConfigProperty(name = "nifi.config.properties.custom", defaultValue = "custom.properties")
    private String defaultCustomPropertiesFile;

    @Inject
    @ConfigProperty(name = "nifi.config.properties.internal",
            defaultValue = "nifi_internal.properties")
    private String internalPropertiesFile;

    @Inject
    @ConfigProperty(name = "nifi.config.properties.comments",
            defaultValue = "nifi_internal_comments.properties")
    private String internalPropertiesCommentsFile;

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
     * Produces a {@link BasePropertiesManager} instance configured with
     * NiFi resource paths and the given properties provider.
     *
     * @param provider the properties provider for retrieving configuration values
     * @return configured {@link BasePropertiesManager} instance
     */
    @Produces
    public BasePropertiesManager basePropertiesManager(PropertiesProvider provider) {
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
                provider
        ));
    }

}
