package com.robsartin.marshal;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class GraphState implements Invariant {

    private final Map<Node, NodeSpec> specs = new IdentityHashMap<>();
    private final Map<Node, Status> status = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> successors = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> predecessors = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> conflicts = new IdentityHashMap<>();
    private final Map<Node, Integer> remainingPreds = new IdentityHashMap<>();

    private static Set<Node> idSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<Node> identityCopy(Set<Node> src) {
        Set<Node> copy = idSet();
        copy.addAll(src);
        return copy;
    }

    // ---- mutation -------------------------------------------------------

    public void addNode(NodeSpec spec) {
        Node n = spec.behavior();
        if (specs.containsKey(n)) return; // idempotent
        specs.put(n, spec);
        status.put(n, Status.WAITING);
        successors.put(n, idSet());
        predecessors.put(n, idSet());
        conflicts.put(n, idSet());
        remainingPreds.put(n, 0);
        for (Node p : spec.predecessors()) addEdge(p, n);
        for (Node c : spec.conflicts()) addConflict(n, c);
        assert holds();
    }

    public void addEdge(Node predecessor, Node successor) {
        require(predecessor);
        require(successor);
        if (successors.get(predecessor).add(successor)) {
            predecessors.get(successor).add(predecessor);
            if (status.get(predecessor) != Status.COMPLETED) {
                remainingPreds.merge(successor, 1, Integer::sum);
            }
        }
        assert holds();
    }

    public void removeEdge(Node predecessor, Node successor) {
        if (successors.getOrDefault(predecessor, Set.of()).remove(successor)) {
            predecessors.get(successor).remove(predecessor);
            if (status.get(predecessor) != Status.COMPLETED) {
                remainingPreds.merge(successor, -1, Integer::sum);
            }
        }
        assert holds();
    }

    public void removeNode(Node n) {
        if (!specs.containsKey(n)) return;
        for (Node s : identityCopy(successors.get(n))) removeEdge(n, s);
        for (Node p : identityCopy(predecessors.get(n))) removeEdge(p, n);
        for (Node c : identityCopy(conflicts.get(n))) removeConflict(n, c);
        specs.remove(n);
        status.remove(n);
        successors.remove(n);
        predecessors.remove(n);
        conflicts.remove(n);
        remainingPreds.remove(n);
        assert holds();
    }

    // addConflict/removeConflict fully exercised in Task 2b; defined here so invariant() is complete.
    public void addConflict(Node a, Node b) {
        require(a);
        require(b);
        if (a == b) throw new IllegalArgumentException("a node cannot conflict with itself");
        conflicts.get(a).add(b);
        conflicts.get(b).add(a);
        assert holds();
    }

    public void removeConflict(Node a, Node b) {
        conflicts.getOrDefault(a, Set.of()).remove(b);
        conflicts.getOrDefault(b, Set.of()).remove(a);
        assert holds();
    }

    public void markReady(Node n) {
        expect(n, Status.WAITING);
        if (remainingPreds.get(n) != 0) throw new IllegalStateException("preds unmet: " + n);
        status.put(n, Status.READY);
        assert holds();
    }

    public void markRunning(Node n) {
        expect(n, Status.READY);
        status.put(n, Status.RUNNING);
        assert holds();
    }

    public void markCompleted(Node n) {
        expect(n, Status.RUNNING);
        status.put(n, Status.COMPLETED);
        for (Node s : successors.get(n)) remainingPreds.merge(s, -1, Integer::sum);
        assert holds();
    }

    public void fail(Node n, Status cause) {
        if (cause != Status.FAILED && cause != Status.TIMED_OUT) {
            throw new IllegalArgumentException("cause must be FAILED or TIMED_OUT: " + cause);
        }
        expect(n, Status.RUNNING);
        status.put(n, cause);
        for (Node s : Set.copyOf(successors.get(n))) skip(s);
        assert holds();
    }

    private void skip(Node n) {
        Status cur = status.get(n);
        if (cur == Status.COMPLETED
                || cur == Status.FAILED
                || cur == Status.TIMED_OUT
                || cur == Status.SKIPPED
                || cur == Status.RUNNING
                || cur == Status.UNREACHABLE) {
            return; // already terminal or in-flight; do not disturb
        }
        status.put(n, Status.SKIPPED);
        for (Node s : Set.copyOf(successors.get(n))) skip(s);
    }

    public Set<Node> readyPromotable() {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : specs.keySet()) {
            if (status.get(n) == Status.WAITING && remainingPreds.get(n) == 0) out.add(n);
        }
        return out;
    }

    private void expect(Node n, Status expected) {
        require(n);
        if (status.get(n) != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status.get(n) + " for " + n);
        }
    }

    // ---- queries --------------------------------------------------------

    public boolean contains(Node n) {
        return specs.containsKey(n);
    }

    public Set<Node> nodes() {
        return Collections.unmodifiableSet(specs.keySet());
    }

    public Status status(Node n) {
        return status.get(n);
    }

    /** Null-safe completed check; unknown nodes are treated as not completed. */
    public boolean isCompletedSafe(Node n) {
        return status.get(n) == Status.COMPLETED;
    }

    public NodeSpec spec(Node n) {
        return specs.get(n);
    }

    public int remainingPreds(Node n) {
        return remainingPreds.get(n);
    }

    public Set<Node> successors(Node n) {
        return Collections.unmodifiableSet(successors.get(n));
    }

    public Set<Node> predecessors(Node n) {
        return Collections.unmodifiableSet(predecessors.get(n));
    }

    public Set<Node> conflicts(Node n) {
        return Collections.unmodifiableSet(conflicts.get(n));
    }

    public boolean wouldIntroduceCycle(Node predecessor, Node successor) {
        // adding predecessor->successor creates a cycle iff predecessor is already reachable from successor
        if (predecessor == successor) return true;
        var stack = new ArrayDeque<Node>();
        var seen = idSet();
        stack.push(successor);
        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            if (cur == predecessor) return true;
            if (!seen.add(cur)) continue;
            for (Node s : successors.getOrDefault(cur, Set.of())) stack.push(s);
        }
        return false;
    }

    // ---- invariant ------------------------------------------------------

    private boolean holds() {
        invariant();
        return true;
    }

    @Override
    public void invariant() {
        for (Node a : specs.keySet()) {
            if (conflicts.get(a).contains(a)) throw new IllegalStateException("conflict irreflexive violated: " + a);
            for (Node b : successors.get(a)) {
                if (!specs.containsKey(b)) throw new IllegalStateException("dangling successor " + b);
                if (!predecessors.get(b).contains(a))
                    throw new IllegalStateException("transpose violated: " + a + "->" + b);
            }
            for (Node p : predecessors.get(a)) {
                if (!specs.containsKey(p)) throw new IllegalStateException("dangling predecessor " + p);
                if (!successors.get(p).contains(a))
                    throw new IllegalStateException("transpose violated: " + p + "->" + a);
            }
            for (Node b : conflicts.get(a)) {
                if (!specs.containsKey(b)) throw new IllegalStateException("dangling conflict " + b);
                if (!conflicts.get(b).contains(a))
                    throw new IllegalStateException("conflict symmetry violated: " + a + "," + b);
            }
            long unmet = predecessors.get(a).stream()
                    .filter(p -> status.get(p) != Status.COMPLETED)
                    .count();
            if (remainingPreds.get(a) != unmet) {
                throw new IllegalStateException(
                        "remainingPreds stale for " + a + ": " + remainingPreds.get(a) + " != " + unmet);
            }
        }
    }

    private void require(Node n) {
        if (!specs.containsKey(n)) throw new IllegalArgumentException("unknown node: " + n);
    }
}
