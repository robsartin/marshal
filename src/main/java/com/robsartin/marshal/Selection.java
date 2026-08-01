package com.robsartin.marshal;

import java.util.*;

public final class Selection {
    private Selection() {}

    public record Dispatch(Node node, ExecutionKind lane) {}

    public static List<Dispatch> select(
            GraphState g, Set<Node> ready, Set<Node> running, int freeCpuPermits, int freeIoPermits) {

        List<Node> candidates = new ArrayList<>(ready);
        candidates.sort(
                Comparator.comparingInt((Node n) -> g.spec(n).priority()).reversed());

        Set<Node> committed = Collections.newSetFromMap(new IdentityHashMap<>());
        committed.addAll(running);
        List<Dispatch> out = new ArrayList<>();
        int cpu = freeCpuPermits, io = freeIoPermits;

        for (Node n : candidates) {
            if (intersects(g.conflicts(n), committed)) continue;
            ExecutionKind lane = g.spec(n).kind();
            if (lane == ExecutionKind.CPU) {
                if (cpu <= 0) continue;
                cpu--;
            } else {
                if (io <= 0) continue;
                io--;
            }
            committed.add(n);
            out.add(new Dispatch(n, lane));
        }
        return out;
    }

    private static boolean intersects(Set<Node> a, Set<Node> b) {
        Set<Node> small = a.size() <= b.size() ? a : b;
        Set<Node> large = small == a ? b : a;
        for (Node n : small) if (large.contains(n)) return true;
        return false;
    }
}
