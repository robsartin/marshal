package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
}
