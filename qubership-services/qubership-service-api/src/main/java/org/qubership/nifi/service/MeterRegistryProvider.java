package org.qubership.nifi.service;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.nifi.controller.ControllerService;

/**
 * Controller service providing PrometheusMeterRegistry.
 */
public interface MeterRegistryProvider extends ControllerService {

    /**
     * Provide Meter Registry.
     * @return MeterRegistry
     */
    PrometheusMeterRegistry getMeterRegistry();
}
