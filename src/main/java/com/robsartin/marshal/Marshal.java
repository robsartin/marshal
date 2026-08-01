package com.robsartin.marshal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Deterministic single-threaded scheduler (MVP). Owns a {@link GraphState}, dispatches
 * ready nodes selected by {@link Selection} to an injected lane {@link Executor}, and
 * drives an event loop to quiescence.
 *
 * <p>MVP scope: buffered mutations are drained but not applied (Task 7), and node
 * timeouts are not enforced (Task 9).
 */
public final class Marshal {
    private final GraphState g = new GraphState();
    private final Executor ioLane;
    private final Executor cpuLane;
    private final int cpuPermits;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Map<Node, Throwable> failures = new IdentityHashMap<>();

    public Marshal(Executor ioLane, Executor cpuLane, int cpuPermits) {
        this.ioLane = ioLane;
        this.cpuLane = cpuLane;
        this.cpuPermits = cpuPermits;
    }

    public Node register(NodeSpec spec) {
        // Predecessors/conflicts referenced by this spec must already be registered; that
        // ordering requirement is the caller's responsibility (GraphState.addNode wires
        // declared predecessor edges immediately and requires them to exist).
        g.addNode(spec);
        return spec.behavior();
    }

    public void conflict(Node a, Node b) {
        g.addConflict(a, b);
    }

    public void conflictGroup(Set<Node> nodes) {
        List<Node> l = new ArrayList<>(nodes);
        for (int i = 0; i < l.size(); i++) {
            for (int j = i + 1; j < l.size(); j++) g.addConflict(l.get(i), l.get(j));
        }
    }

    public RunReport run() {
        int freeCpu = cpuPermits;
        int inFlight = 0;

        promoteReady();
        List<Selection.Dispatch> toStart = Selection.select(g, ready(), running(), freeCpu, Integer.MAX_VALUE);
        for (Selection.Dispatch d : toStart) {
            freeCpu = dispatch(d, freeCpu);
            inFlight++;
        }

        while (inFlight > 0) {
            Event ev = take();
            if (ev instanceof Event.Completed c) {
                inFlight--;
                Node n = c.node();
                if (g.status(n) != Status.RUNNING) continue; // idempotency guard (used in Task 9)
                if (c.outcome() instanceof Outcome.Failure f) {
                    g.fail(n, Status.FAILED);
                    failures.put(n, f.cause());
                } else {
                    g.markCompleted(n);
                }
                // Buffered mutations (c.mutations()) are intentionally drained-but-not-applied
                // in the MVP; applying them to the graph is Task 7.
                if (g.spec(n).kind() == ExecutionKind.CPU) freeCpu++;

                promoteReady();
                List<Selection.Dispatch> next = Selection.select(g, ready(), running(), freeCpu, Integer.MAX_VALUE);
                for (Selection.Dispatch d : next) {
                    freeCpu = dispatch(d, freeCpu);
                    inFlight++;
                }
            }
        }
        return report();
    }

    private int dispatch(Selection.Dispatch d, int freeCpu) {
        Node n = d.node();
        g.markRunning(n);
        Executor lane = d.lane() == ExecutionKind.CPU ? cpuLane : ioLane;
        lane.execute(() -> {
            BufferingExecutionContext ctx = new BufferingExecutionContext(g::isCompletedSafe);
            Outcome outcome;
            try {
                n.execute(ctx);
                outcome = Outcome.SUCCESS;
            } catch (Throwable t) {
                outcome = new Outcome.Failure(t);
            }
            events.add(new Event.Completed(n, outcome, ctx.drain()));
        });
        return d.lane() == ExecutionKind.CPU ? freeCpu - 1 : freeCpu;
    }

    private void promoteReady() {
        for (Node n : g.readyPromotable()) g.markReady(n);
    }

    private Set<Node> ready() {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : g.nodes()) if (g.status(n) == Status.READY) s.add(n);
        return s;
    }

    private Set<Node> running() {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : g.nodes()) if (g.status(n) == Status.RUNNING) s.add(n);
        return s;
    }

    private Event take() {
        try {
            return events.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private RunReport report() {
        Map<Node, Status> statuses = new IdentityHashMap<>();
        for (Node n : g.nodes()) {
            Status s = g.status(n);
            statuses.put(n, s == Status.WAITING || s == Status.READY ? Status.UNREACHABLE : s);
        }
        return new RunReport(statuses, Map.copyOf(failures));
    }
}
