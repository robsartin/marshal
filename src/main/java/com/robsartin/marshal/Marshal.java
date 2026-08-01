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
 * <p>On successful completion, a node's buffered mutations ({@link BufferingExecutionContext})
 * are validated and applied atomically to the graph before the node is marked completed. If any
 * mutation in the batch is invalid (e.g. an edge that would introduce a cycle), the whole batch
 * is rejected and the node is marked {@link Status#FAILED} instead, so its dependents skip.
 *
 * <p>MVP scope: node timeouts are not enforced (Task 9).
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
                    try {
                        applyMutations(n, c.mutations());
                        g.markCompleted(n);
                    } catch (MutationRejected rejected) {
                        g.fail(n, Status.FAILED);
                        failures.put(n, rejected);
                    }
                }
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

    /**
     * Validates a completing node's buffered mutation batch, then applies it atomically. If any
     * mutation is invalid (currently: an {@link Mutation.AddEdge} that would introduce a cycle
     * between two nodes already present in the graph), no mutation in the batch is applied and
     * {@link MutationRejected} is thrown; the caller marks the originating node {@link
     * Status#FAILED}.
     *
     * <p>Mutations are applied in buffer order, matching {@link BufferingExecutionContext}'s call
     * order (callers add a node before wiring edges to it).
     */
    private void applyMutations(Node origin, List<Mutation> batch) {
        for (Mutation mu : batch) {
            if (mu instanceof Mutation.AddEdge e) {
                if (g.contains(e.predecessor())
                        && g.contains(e.successor())
                        && g.wouldIntroduceCycle(e.predecessor(), e.successor())) {
                    throw new MutationRejected("edge would introduce a cycle: " + e);
                }
            }
        }
        for (Mutation mu : batch) {
            switch (mu) {
                case Mutation.AddNode a -> g.addNode(a.spec());
                case Mutation.RemoveNode rn -> g.removeNode(rn.node());
                case Mutation.AddEdge e -> g.addEdge(e.predecessor(), e.successor());
                case Mutation.RemoveEdge e -> g.removeEdge(e.predecessor(), e.successor());
                case Mutation.AddConflict cf -> g.addConflict(cf.a(), cf.b());
            }
        }
        promoteReady();
    }

    private static final class MutationRejected extends RuntimeException {
        MutationRejected(String message) {
            super(message);
        }
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
