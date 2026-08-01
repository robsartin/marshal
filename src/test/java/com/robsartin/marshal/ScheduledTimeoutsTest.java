package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the production {@link Timeouts} implementation. {@link Marshal}'s own tests
 * exercise the timeout *policy* (interrupt + report) via {@code ManualTimeouts}, a synchronous
 * test double; these tests instead drive the real background-scheduler wiring so {@code arm},
 * {@code cancel}, and {@code close} run for real.
 */
class ScheduledTimeoutsTest {
    @Test
    void armFiresTheCallbackAfterTheBudgetElapses() throws Exception {
        try (ScheduledTimeouts timeouts = new ScheduledTimeouts()) {
            CountDownLatch fired = new CountDownLatch(1);
            Node node = ctx -> {};

            timeouts.arm(node, Duration.ofMillis(20), fired::countDown);

            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancelBeforeExpiryPreventsTheCallbackFromFiring() throws Exception {
        try (ScheduledTimeouts timeouts = new ScheduledTimeouts()) {
            CountDownLatch fired = new CountDownLatch(1);
            Node node = ctx -> {};

            timeouts.arm(node, Duration.ofMillis(200), fired::countDown);
            timeouts.cancel(node);

            assertThat(fired.await(400, TimeUnit.MILLISECONDS)).isFalse();
        }
    }

    @Test
    void cancelOnANeverArmedNodeIsANoOp() {
        try (ScheduledTimeouts timeouts = new ScheduledTimeouts()) {
            Node neverArmed = ctx -> {};
            timeouts.cancel(neverArmed); // must not throw
        }
    }

    @Test
    void closeShutsDownTheSchedulerRejectingFurtherArms() {
        ScheduledTimeouts timeouts = new ScheduledTimeouts();
        timeouts.close();

        assertThatThrownBy(() -> timeouts.arm(ctx -> {}, Duration.ofMillis(10), () -> {}))
                .isInstanceOf(RejectedExecutionException.class);
    }
}
