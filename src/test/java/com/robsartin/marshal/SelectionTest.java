package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;

class SelectionTest {
    private static Set<Node> idSet(Node... ns) {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(Arrays.asList(ns));
        return s;
    }

    @Test
    void picksHighestPriorityFirstAndRespectsConflicts() {
        GraphState g = new GraphState();
        Node hi = ctx -> {}, lo = ctx -> {}, foe = ctx -> {};
        g.addNode(NodeSpec.of(hi).priority(10).build());
        g.addNode(NodeSpec.of(lo).priority(1).build());
        g.addNode(NodeSpec.of(foe).priority(5).build());
        g.addConflict(hi, foe);                              // hi and foe cannot co-run

        List<Selection.Dispatch> out =
            Selection.select(g, idSet(hi, lo, foe), idSet(), 8, 8);

        // hi wins on priority; foe is then blocked by the just-selected hi; lo also dispatched
        assertThat(out).extracting(Selection.Dispatch::node).containsExactly(hi, lo);
    }

    @Test
    void cpuPermitsCapCpuLaneButNotIoLane() {
        GraphState g = new GraphState();
        Node cpu1 = ctx -> {}, cpu2 = ctx -> {}, io1 = ctx -> {};
        g.addNode(NodeSpec.of(cpu1).priority(9).kind(ExecutionKind.CPU).build());
        g.addNode(NodeSpec.of(cpu2).priority(8).kind(ExecutionKind.CPU).build());
        g.addNode(NodeSpec.of(io1).priority(1).kind(ExecutionKind.IO).build());

        List<Selection.Dispatch> out =
            Selection.select(g, idSet(cpu1, cpu2, io1), idSet(), 1, 8);   // only 1 CPU permit

        assertThat(out).extracting(Selection.Dispatch::node).containsExactly(cpu1, io1);
    }
}
