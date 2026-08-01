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
 * are fully validated against the graph before any of them are applied, then applied to the
 * graph before the node is marked completed. Validating the whole batch up front (rather than
 * catching failures mid-apply) is what makes rejection atomic: if any mutation in the batch is
 * invalid (e.g. an edge or conflict referencing a node the batch never adds, or an edge that
 * would introduce a cycle), nothing in the batch is applied and the node is marked {@link
 * Status#FAILED} instead, so its dependents skip. See {@link #applyMutations} for the exact
 * validation rules and a documented limitation around cycles formed entirely through
 * same-batch-added nodes.
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
     * Validates a completing node's buffered mutation batch, then applies it. Validation runs
     * entirely before any mutation is applied, so a rejected batch is atomic: nothing in it is
     * applied and none of {@link GraphState}'s own precondition checks (which throw raw {@link
     * IllegalArgumentException}/{@link IllegalStateException}, not {@link MutationRejected}) can
     * be reached during the apply pass. If any mutation is invalid, {@link MutationRejected} is
     * thrown and the caller marks the originating node {@link Status#FAILED}.
     *
     * <p>Validation simulates the batch's node membership in buffer order (an {@link
     * Mutation.AddNode} makes its node valid to reference later in the same batch; an {@link
     * Mutation.RemoveNode} makes it invalid to reference later) and rejects:
     *
     * <ul>
     *   <li>an {@link Mutation.AddEdge} or {@link Mutation.AddConflict} referencing a node not
     *       present at that point in the batch ("dangling reference")
     *   <li>an {@link Mutation.AddConflict} between a node and itself
     *   <li>an {@link Mutation.AddEdge} that would introduce a cycle, checked against the current
     *       graph ({@code g}) for edges whose endpoints both already exist there
     * </ul>
     *
     * <p><b>Minor limitation:</b> a cycle formed entirely through nodes added earlier in the same
     * batch is not detected here, since {@link GraphState#wouldIntroduceCycle} is only consulted
     * for endpoints that exist in {@code g} before this batch. This does not corrupt the graph or
     * crash the scheduler — {@link GraphState}'s {@code invariant()} does not enforce acyclicity —
     * it just leaves the affected nodes {@link Status#UNREACHABLE} at quiescence (their
     * predecessor count never reaches zero). Full mid-batch cycle validation is out of scope here.
     *
     * <p>Mutations are applied in buffer order, matching {@link BufferingExecutionContext}'s call
     * order (callers add a node before wiring edges to it).
     */
    private void applyMutations(Node origin, List<Mutation> batch) {
        Set<Node> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(g.nodes());
        for (Mutation mu : batch) {
            switch (mu) {
                case Mutation.AddNode a -> present.add(a.spec().behavior());
                case Mutation.RemoveNode rn -> present.remove(rn.node());
                case Mutation.AddEdge e -> {
                    if (!present.contains(e.predecessor()) || !present.contains(e.successor())) {
                        throw new MutationRejected("dangling edge reference: " + e);
                    }
                    if (g.contains(e.predecessor())
                            && g.contains(e.successor())
                            && g.wouldIntroduceCycle(e.predecessor(), e.successor())) {
                        throw new MutationRejected("edge would introduce a cycle: " + e);
                    }
                }
                case Mutation.RemoveEdge e -> {
                    // GraphState.removeEdge no-ops on an unknown edge/node; nothing to validate.
                }
                case Mutation.AddConflict cf -> {
                    if (!present.contains(cf.a()) || !present.contains(cf.b())) {
                        throw new MutationRejected("dangling conflict reference: " + cf);
                    }
                    if (cf.a() == cf.b()) {
                        throw new MutationRejected("self-conflict: " + cf);
                    }
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
