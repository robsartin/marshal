package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

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
        Marshal m = Marshal.create();
        int n = 4;
        AtomicInteger maxObserved = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(n);

        Node[] ns = new Node[n];
        for (int i = 0; i < ns.length; i++) {
            ns[i] = ctx -> {
                maxObserved.accumulateAndGet(barrier.getParties(), Math::max);
                try {
                    barrier.await(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            m.register(NodeSpec.of(ns[i]).kind(ExecutionKind.IO).build());
        }
        m.run();
        assertThat(maxObserved.get()).isEqualTo(n); // all n rendezvoused: genuine parallelism
    }
}
