package org.qubership.nifi.utils;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class GateNegativeControlTest {
    @Test
    void failsOnPurpose() {
        fail("Throwaway gate check, do not merge.");
    }
}
