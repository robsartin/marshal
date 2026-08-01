package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphStateStatusTest {
    private static NodeSpec spec(Node n) { return NodeSpec.of(n).build(); }

    @Test
    void completingPredecessorPromotesSuccessor() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b));
        g.addEdge(a, b);

        g.markReady(a); g.markRunning(a); g.markCompleted(a);

        assertThat(g.remainingPreds(b)).isEqualTo(0);
        assertThat(g.readyPromotable()).containsExactly(b);
        g.invariant();
    }

    @Test
    void failureSkipsTransitiveDependentsButNotSiblings() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {}, indep = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b)); g.addNode(spec(c)); g.addNode(spec(indep));
        g.addEdge(a, b);
        g.addEdge(b, c);

        g.markReady(a); g.markRunning(a);
        g.fail(a, Status.FAILED);

        assertThat(g.status(b)).isEqualTo(Status.SKIPPED);
        assertThat(g.status(c)).isEqualTo(Status.SKIPPED);
        assertThat(g.status(indep)).isEqualTo(Status.WAITING);
        g.invariant();
    }
}
