package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
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

    @Test
    void threeArgConstructorUsesNoOpTimeoutsAndIgnoresDeclaredTimeout() {
        // The 3-arg Marshal(ioLane, cpuLane, cpuPermits) constructor defaults to a no-op
        // Timeouts: arm() is a deliberate no-op, so a node's declared timeout is never actually
        // enforced -- it simply runs to completion.
        var inline = new InlineExecutor();
        Marshal m = new Marshal(inline, inline, 4);
        Node quick = ctx -> {};
        m.register(
                NodeSpec.of(quick).timeout(Duration.ofMillis(1)).name("quick").build());

        RunReport r = m.run();

        assertThat(r.statusOf(quick)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void fourArgCreateFactoryProducesAWorkingMarshal() {
        var inline = new InlineExecutor();
        ManualTimeouts timeouts = new ManualTimeouts();
        Marshal m = Marshal.create(4, inline, inline, timeouts);
        Node n = ctx -> {};
        m.register(NodeSpec.of(n).name("n").build());

        RunReport r = m.run();

        assertThat(r.statusOf(n)).isEqualTo(Status.COMPLETED);
    }
}
