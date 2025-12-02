package org.qubership.nifi.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.nifi.controller.ControllerService;

/**
 * Controller service providing MeterRegistry.
 */
public interface MeterRegistryProvider extends ControllerService {

    /**
     * Provide Meter Registry.
     * @return MeterRegistry
     */
    MeterRegistry getMeterRegistry();
}
