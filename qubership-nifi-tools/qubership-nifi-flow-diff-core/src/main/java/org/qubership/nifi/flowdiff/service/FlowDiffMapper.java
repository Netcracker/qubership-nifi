package org.qubership.nifi.flowdiff.service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Jackson mapper shared across a run. It is left at its default configuration and never reconfigured after class
 * initialization, so it is safe to share between threads.
 */
public final class FlowDiffMapper {

    /** The shared mapper instance. */
    public static final ObjectMapper INSTANCE = new ObjectMapper();

    private FlowDiffMapper() {
    }
}
