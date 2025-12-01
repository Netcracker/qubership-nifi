package org.qubership.nifi.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.nifi.controller.ControllerService;

public interface ProvideMeterRegistry extends ControllerService {

    /**
     * Provide Meter Registry.
     * @return
     */
    MeterRegistry getMeterRegistry();
}
