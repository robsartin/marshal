package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphStateEdgesTest {
    private static NodeSpec spec(Node n) { return NodeSpec.of(n).build(); }

    @Test
    void addEdgeMaintainsBothDirectionsAndRemainingCount() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);

        assertThat(g.successors(a)).containsExactly(b);
        assertThat(g.predecessors(b)).containsExactly(a);
        assertThat(g.remainingPreds(b)).isEqualTo(1);
        assertThat(g.remainingPreds(a)).isEqualTo(0);
        g.invariant();                    // must not throw
    }

    @Test
    void removeNodePurgesDanglingReferences() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);
        g.removeNode(a);

        assertThat(g.contains(a)).isFalse();
        assertThat(g.predecessors(b)).isEmpty();
        assertThat(g.remainingPreds(b)).isEqualTo(0);
        g.invariant();
    }

    @Test
    void wouldIntroduceCycleDetectsBackEdge() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b)); g.addNode(spec(c));
        g.addEdge(a, b);
        g.addEdge(b, c);
        assertThat(g.wouldIntroduceCycle(c, a)).isTrue();   // c->a closes a->b->c->a
        assertThat(g.wouldIntroduceCycle(a, c)).isFalse();  // a->c is a valid forward edge
    }
}
