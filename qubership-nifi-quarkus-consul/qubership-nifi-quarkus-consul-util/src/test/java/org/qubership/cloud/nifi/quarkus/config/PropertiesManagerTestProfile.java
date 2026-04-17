package org.qubership.cloud.nifi.quarkus.config;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class PropertiesManagerTestProfile
        implements QuarkusTestProfile {

    public PropertiesManagerTestProfile () {
        //default constructor
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "NIFI_HOME", "./test-location",
            "nifi.home", "./test-location2",
            "nifi.env.prop1", "1",
            "nifi.env.prop2", "1"
        );
    }
}
