package com.robsartin.marshal;

import java.util.Set;

/** Handle a running node uses to read state and buffer graph mutations. */
public interface ExecutionContext {
    /** The id of the {@link Marshal#run()} this node is executing within. */
    String runId();

    boolean isCompleted(Node node);

    void addNode(NodeSpec spec);

    void removeNode(Node node);

    void addEdge(Node predecessor, Node successor);

    void removeEdge(Node predecessor, Node successor);

    void conflict(Node a, Node b);

    void conflictGroup(Set<Node> nodes);
}
