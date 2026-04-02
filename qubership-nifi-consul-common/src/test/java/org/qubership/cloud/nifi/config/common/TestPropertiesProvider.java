package org.qubership.cloud.nifi.config.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TestPropertiesProvider
        implements PropertiesProvider {

    private static final Map<String, String> TEST_VALUES = new HashMap<>();
    static {
        TEST_VALUES.put("logger.org.qubership", "DEBUG");
        TEST_VALUES.put("logger.org.apache.nifi.registry.StdErr", "INFO");
        TEST_VALUES.put("nifi.registry.db.maxConnections", "15");
        TEST_VALUES.put("nifi.registry.security.user.oidc.connect.timeout", "10 secs");
        TEST_VALUES.put("test.non-matching.property", "Some value");
        TEST_VALUES.put("nifi.registry.security.identity.mapping.transform.dn", "NONE");
    }
    private Map<String, String> testValues = new HashMap<>(TEST_VALUES);

    @Override
    public Set<String> getAllPropertyNamesFromSource() {
        return testValues.keySet();
    }

    @Override
    public String getPropertyValue(String propertyName) {
        return testValues.get(propertyName);
    }

    public void putProperty(String propName, String propValue) {
        testValues.put(propName, propValue);
    }

    public void removeProperty(String propName) {
        testValues.remove(propName);
    }
}
