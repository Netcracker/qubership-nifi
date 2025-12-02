package org.qubership.nifi.reporting;

import org.apache.nifi.controller.ControllerServiceLookup;
import org.apache.nifi.util.MockControllerServiceLookup;

import java.util.HashMap;
import java.util.Map;

public class SimpleMockControllerServiceLookup extends MockControllerServiceLookup {
    private final Map<String, Object> services = new HashMap<>();

    public void addControllerService(String identifier, Object service) {
        this.services.put(identifier, service);
    }
}
