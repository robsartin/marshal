package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.ManualTimeouts;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class MarshalTimeoutTest {
    @Test
    void timedOutNodeIsReportedAndDependentsSkipped() throws Exception {
        ManualTimeouts timeouts = new ManualTimeouts();
        Executor pool = Executors.newVirtualThreadPerTaskExecutor();
        Marshal m = new Marshal(pool, pool, 4, timeouts);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Node slow = ctx -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Node dependent = ctx -> {};
        m.register(NodeSpec.of(slow).timeout(Duration.ofSeconds(1)).name("slow").build());
        m.register(NodeSpec.of(dependent).predecessors(Set.of(slow)).build());

        // Run on a background thread so the test can trigger the timeout deterministically.
        var runner = Executors.newSingleThreadExecutor();
        var future = runner.submit(m::run);

        started.await();
        timeouts.expire(slow); // fire the timeout: interrupts slow, posts TimedOut
        RunReport r = future.get();
        release.countDown();

        assertThat(r.statusOf(slow)).isEqualTo(Status.TIMED_OUT);
        assertThat(r.statusOf(dependent)).isEqualTo(Status.SKIPPED);
        runner.shutdownNow();
    }
}
