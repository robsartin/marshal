package com.robsartin.marshal.support;

import com.robsartin.marshal.Node;
import com.robsartin.marshal.Timeouts;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ManualTimeouts implements Timeouts {
    private final Map<Node, Runnable> armed = new IdentityHashMap<>();

    @Override
    public synchronized void arm(Node node, Duration budget, Runnable onExpiry) {
        armed.put(node, onExpiry);
    }

    @Override
    public synchronized void cancel(Node node) {
        armed.remove(node);
    }

    public synchronized void expire(Node node) {
        Runnable r = armed.remove(node);
        if (r != null) r.run();
    }
}
