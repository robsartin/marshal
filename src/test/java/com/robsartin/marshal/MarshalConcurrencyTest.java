package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MarshalConcurrencyTest {
    @Test
    void conflictingNodesNeverRunConcurrently() throws Exception {
        Marshal m = Marshal.create();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();

        Runnable body = () -> {
            int now = concurrent.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
        };
        Node a = ctx -> body.run();
        Node b = ctx -> body.run();
        Node c = ctx -> body.run();
        m.register(NodeSpec.of(a).build());
        m.register(NodeSpec.of(b).build());
        m.register(NodeSpec.of(c).build());
        m.conflictGroup(Set.of(a, b, c)); // mutually exclusive

        m.run();

        assertThat(maxObserved.get()).isEqualTo(1); // never two at once
    }

    @Test
    void independentNodesRunInParallel() throws Exception {
        int n = 4;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(n);
        Marshal m = Marshal.create();
        List<Node> nodes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            Node node = ctx -> {
                int now = concurrent.incrementAndGet();
                maxObserved.accumulateAndGet(now, Math::max); // LIVE count, not getParties()
                try {
                    barrier.await(2, TimeUnit.SECONDS); // forces all n to overlap; bounded so no infinite hang
                } catch (Exception e) {
                    throw new RuntimeException(e); // barrier timeout/broken -> node FAILS
                }
                concurrent.decrementAndGet();
            };
            nodes.add(node);
            m.register(NodeSpec.of(node).build());
        }

        RunReport r = m.run();

        assertThat(maxObserved.get()).isEqualTo(n); // only reachable if all n truly overlap
        for (Node node : nodes) {
            assertThat(r.statusOf(node)).isEqualTo(Status.COMPLETED); // barrier timeout -> FAILED, caught here
        }
    }

    /**
     * Reproduces the ctx.isCompleted() / GraphState data race: node C polls ctx.isCompleted(A)
     * from its own worker thread in a tight loop while the scheduler thread concurrently mutates
     * GraphState (markRunning/markCompleted/markReady for many other nodes, driving repeated
     * IdentityHashMap growth). Before the fix, isCompleted() was backed by
     * {@code g::isCompletedSafe}, an unsynchronized read of GraphState's {@code status}
     * IdentityHashMap from a worker thread while the scheduler thread writes it concurrently --
     * an unsynchronized-map data race that can produce stale reads or (per IdentityHashMap's
     * resize behavior under concurrent mutation) hang. Real threads (Marshal.create()) are
     * required: only genuine cross-thread concurrency exercises this.
     */
    @Test
    @Timeout(30)
    void isCompletedPolledFromWorkerThreadDuringConcurrentGraphMutationDoesNotRaceGraphState() throws Exception {
        Marshal m = Marshal.create();
        Node a = ctx -> {};
        Node b = ctx -> {}; // depends on a
        AtomicInteger pollCount = new AtomicInteger();
        Node c = ctx -> {
            // Time-boxed (not iteration-boxed) tight loop: guarantees c's worker thread keeps
            // hammering ctx.isCompleted(a) for the whole window the scheduler thread spends
            // driving the churn nodes below through WAITING->READY->RUNNING->COMPLETED, however
            // fast or slow that happens to be on this machine.
            long deadline = System.nanoTime() + java.time.Duration.ofMillis(300).toNanos();
            while (System.nanoTime() < deadline) {
                ctx.isCompleted(a);
                pollCount.incrementAndGet();
            }
        };
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(Set.of(a)).name("b").build());
        m.register(NodeSpec.of(c).name("c").build());

        // Churn: many independent nodes, each completing (several GraphState status.put() calls
        // per node: WAITING->READY->RUNNING->COMPLETED) while c's tight polling loop is running,
        // to force repeated IdentityHashMap growth concurrently with c's reads.
        List<Node> churners = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Node churner = ctx -> {};
            churners.add(churner);
            m.register(NodeSpec.of(churner).name("churn-" + i).build());
        }

        RunReport r = m.run();

        assertThat(pollCount.get()).isGreaterThan(0); // completed without exception or hang
        assertThat(r.statusOf(a)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(b)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(c)).isEqualTo(Status.COMPLETED);
        for (Node churner : churners) {
            assertThat(r.statusOf(churner)).isEqualTo(Status.COMPLETED);
        }
    }
}
