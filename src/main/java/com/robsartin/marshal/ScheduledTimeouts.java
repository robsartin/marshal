package com.robsartin.marshal;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link Timeouts} backed by a single daemon {@link ScheduledExecutorService}. Safe to share
 * across a {@link Marshal} run; {@link #close()} shuts the scheduler down.
 */
public final class ScheduledTimeouts implements Timeouts, AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "marshal-timeout");
        t.setDaemon(true);
        return t;
    });
    private final Map<Node, ScheduledFuture<?>> armed = new IdentityHashMap<>();

    @Override
    public synchronized void arm(Node node, Duration budget, Runnable onExpiry) {
        armed.put(node, scheduler.schedule(onExpiry, budget.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Override
    public synchronized void cancel(Node node) {
        ScheduledFuture<?> f = armed.remove(node);
        if (f != null) f.cancel(false);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
