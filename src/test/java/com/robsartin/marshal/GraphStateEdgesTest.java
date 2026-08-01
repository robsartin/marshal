package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphStateEdgesTest {
    private static NodeSpec spec(Node n) {
        return NodeSpec.of(n).build();
    }

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
        g.invariant(); // must not throw
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
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addNode(spec(c));
        g.addEdge(a, b);
        g.addEdge(b, c);
        assertThat(g.wouldIntroduceCycle(c, a)).isTrue(); // c->a closes a->b->c->a
        assertThat(g.wouldIntroduceCycle(a, c)).isFalse(); // a->c is a valid forward edge
    }

    @Test
    void invariantHoldsAfterAddAndRemoveEdgesInBothOrders() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addNode(spec(c));

        g.addEdge(a, b);
        g.addEdge(b, c);
        g.addEdge(a, c);
        g.invariant();

        // remove in a different order than they were added
        g.removeEdge(a, c);
        g.invariant();
        g.removeEdge(b, c);
        g.invariant();
        g.removeEdge(a, b);
        g.invariant();

        assertThat(g.successors(a)).isEmpty();
        assertThat(g.predecessors(b)).isEmpty();
        assertThat(g.predecessors(c)).isEmpty();
        assertThat(g.remainingPreds(b)).isEqualTo(0);
        assertThat(g.remainingPreds(c)).isEqualTo(0);

        // re-add and remove via removeNode to exercise the identity-based purge path too
        g.addEdge(a, b);
        g.addEdge(b, c);
        g.invariant();
        g.removeNode(b);
        g.invariant();
        assertThat(g.contains(b)).isFalse();
        assertThat(g.predecessors(c)).isEmpty();
    }

    @Test
    void addNodeIsIdempotentOnReRegistration() {
        GraphState g = new GraphState();
        Node a = ctx -> {};
        NodeSpec original = NodeSpec.of(a).priority(1).name("original").build();
        NodeSpec resubmitted = NodeSpec.of(a).priority(99).name("resubmitted").build();
        g.addNode(original);
        g.addNode(resubmitted); // same identity; must be a no-op, not an overwrite

        assertThat(g.spec(a)).isSameAs(original);
        assertThat(g.spec(a).priority()).isEqualTo(1);
        assertThat(g.nodes()).hasSize(1);
        g.invariant();
    }

    @Test
    void removeEdgeAfterPredecessorAlreadyCompletedDoesNotDoubleDecrement() {
        // markCompleted() already decremented b's remainingPreds when a finished; a later,
        // unrelated removeEdge(a, b) call must not decrement it again (which would drive it
        // negative and desynchronize it from the live-predecessor count invariant checks).
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);
        g.markReady(a);
        g.markRunning(a);
        g.markCompleted(a);
        assertThat(g.remainingPreds(b)).isEqualTo(0);

        g.removeEdge(a, b);

        assertThat(g.remainingPreds(b)).isEqualTo(0);
        assertThat(g.predecessors(b)).isEmpty();
        g.invariant();
    }

    @Test
    void addEdgeFromAlreadyCompletedPredecessorDoesNotBlockSuccessor() {
        // Wiring an edge to a node that already finished must not make its new successor wait:
        // the predecessor's requirement is already satisfied.
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.markReady(a);
        g.markRunning(a);
        g.markCompleted(a);

        g.addEdge(a, b);

        assertThat(g.remainingPreds(b)).isEqualTo(0);
        assertThat(g.predecessors(b)).containsExactly(a);
        g.invariant();
    }

    @Test
    void invariantDetectsDanglingPredecessorNotVisibleFromSuccessorsPass() throws Exception {
        // Corrupts the predecessors index directly (bypassing addEdge) to simulate the
        // corruption class invariant() exists to catch per ADR-0015: a stray entry in
        // predecessors[b] that has no live node and no matching successors edge. The
        // remainingPreds counter is bumped in lockstep so the pre-existing fold check
        // alone would NOT have caught this — only the reverse (predecessors-side)
        // transpose/referential-integrity pass added in this fix does.
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, ghost = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);

        Field predsField = GraphState.class.getDeclaredField("predecessors");
        predsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Node, Set<Node>> predecessors = (Map<Node, Set<Node>>) predsField.get(g);
        predecessors.get(b).add(ghost);

        Field remainingField = GraphState.class.getDeclaredField("remainingPreds");
        remainingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Node, Integer> remainingPreds = (Map<Node, Integer>) remainingField.get(g);
        remainingPreds.merge(b, 1, Integer::sum); // keep the fold check consistent on its own

        assertThatThrownBy(g::invariant).isInstanceOf(IllegalStateException.class);
    }
}
