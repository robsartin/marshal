package com.robsartin.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Buffers graph mutations in call order without touching any graph. Not thread-safe. */
public final class BufferingExecutionContext implements ExecutionContext {
    private final String runId;
    private final Predicate<Node> completedView;
    private final List<Mutation> buffer = new ArrayList<>();

    public BufferingExecutionContext(String runId, Predicate<Node> completedView) {
        this.runId = runId;
        this.completedView = completedView;
    }

    @Override
    public String runId() {
        return runId;
    }

    @Override
    public boolean isCompleted(Node node) {
        return completedView.test(node);
    }

    @Override
    public void addNode(NodeSpec spec) {
        buffer.add(new Mutation.AddNode(spec));
    }

    @Override
    public void removeNode(Node node) {
        buffer.add(new Mutation.RemoveNode(node));
    }

    @Override
    public void addEdge(Node predecessor, Node successor) {
        buffer.add(new Mutation.AddEdge(predecessor, successor));
    }

    @Override
    public void removeEdge(Node predecessor, Node successor) {
        buffer.add(new Mutation.RemoveEdge(predecessor, successor));
    }

    @Override
    public void conflict(Node a, Node b) {
        buffer.add(new Mutation.AddConflict(a, b));
    }

    @Override
    public void conflictGroup(Set<Node> nodes) {
        List<Node> list = new ArrayList<>(nodes);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                buffer.add(new Mutation.AddConflict(list.get(i), list.get(j)));
            }
        }
    }

    /** Returns an immutable snapshot of buffered mutations and clears the buffer. */
    public List<Mutation> drain() {
        List<Mutation> out = List.copyOf(buffer);
        buffer.clear();
        return out;
    }
}
