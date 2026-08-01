package com.robsartin.marshal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void assertionsAreEnabled() {
        boolean enabled = false;
        assert (enabled = true);            // flips only if -ea is on
        assertTrue(enabled, "run tests with -ea");
    }
}
