package org.qubership.cloud.nifi.config;

import java.util.HashSet;
import java.util.Set;

/**
 * Class providing set of readonly properties for qubership-nifi.
 */
public class ReadOnlyProperties {
    private ReadOnlyProperties() {
    }

    /**
     * A set of properties that must not be configured from Configuration Source (e.g. Consul).
     */
    public static final Set<String> READ_ONLY_NIFI_PROPERTIES = new HashSet<>();
    static {
        READ_ONLY_NIFI_PROPERTIES.add("nifi.http-auth-proxying-disabled-schemes");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.http-auth-tunneling-disabled-schemes");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.cluster.base-node-count");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.cluster.start-mode");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.nifi-registry.nar-provider-enabled");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.conf.clean-db-repository");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.conf.clean-configuration");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.extensions.retry.attempts");
        READ_ONLY_NIFI_PROPERTIES.add("nifi.extensions.retry.delay");
    }
}
