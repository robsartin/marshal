package com.robsartin.marshal.support;

import com.robsartin.marshal.Node;
import com.robsartin.marshal.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Naive, obviously-correct shadow of GraphState: stores ground truth, recomputes indexes. */
public final class ReferenceGraphModel {
    public final Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    public final Map<Node, Status> status = new IdentityHashMap<>();
    private final List<Node[]> deps = new ArrayList<>(); // {pred, succ}

    public void addNode(Node n) {
        if (nodes.add(n)) status.put(n, Status.WAITING);
    }

    public void addEdge(Node p, Node s) {
        if (nodes.contains(p) && nodes.contains(s) && !hasEdge(p, s)) deps.add(new Node[] {p, s});
    }

    public void removeEdge(Node p, Node s) {
        deps.removeIf(e -> e[0] == p && e[1] == s);
    }

    public void removeNode(Node n) {
        nodes.remove(n);
        status.remove(n);
        deps.removeIf(e -> e[0] == n || e[1] == n);
    }

    public void setStatus(Node n, Status st) {
        if (nodes.contains(n)) status.put(n, st);
    }

    public boolean hasEdge(Node p, Node s) {
        for (Node[] e : deps) if (e[0] == p && e[1] == s) return true;
        return false;
    }

    public Set<Node> successors(Node n) {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node[] e : deps) if (e[0] == n) out.add(e[1]);
        return out;
    }

    public Set<Node> predecessors(Node n) {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node[] e : deps) if (e[1] == n) out.add(e[0]);
        return out;
    }

    public int remainingPreds(Node n) {
        int c = 0;
        for (Node[] e : deps) if (e[1] == n && status.get(e[0]) != Status.COMPLETED) c++;
        return c;
    }
}
