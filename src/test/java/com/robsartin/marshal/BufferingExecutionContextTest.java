package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BufferingExecutionContextTest {
    @Test
    void buffersMutationsInOrderAndDrainsOnce() {
        Node added = ctx -> {};
        BufferingExecutionContext ctx = new BufferingExecutionContext("run-1", n -> false);
        ctx.addNode(NodeSpec.of(added).build());
        ctx.addEdge(added, added); // order preserved even if nonsensical here

        List<Mutation> drained = ctx.drain();
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0)).isInstanceOf(Mutation.AddNode.class);
        assertThat(drained.get(1)).isInstanceOf(Mutation.AddEdge.class);
        assertThat(ctx.drain()).isEmpty(); // idempotent drain
    }

    @Test
    void conflictGroupExpandsToPairwiseAddConflict() {
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {};
        BufferingExecutionContext ctx = new BufferingExecutionContext("run-1", n -> false);
        ctx.conflictGroup(Set.of(a, b, c));
        long pairs = ctx.drain().stream()
                .filter(m -> m instanceof Mutation.AddConflict)
                .count();
        assertThat(pairs).isEqualTo(3); // {a,b},{a,c},{b,c}
    }

    @Test
    void exposesRunIdPassedAtConstruction() {
        BufferingExecutionContext ctx = new BufferingExecutionContext("run-42", n -> false);
        assertThat(ctx.runId()).isEqualTo("run-42");
    }
}
