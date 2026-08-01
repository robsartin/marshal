package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarshalMutationTest {
    private Marshal newMarshal() {
        var inline = new InlineExecutor();
        return new Marshal(inline, inline, 4);
    }

    @Test
    void nodeAddedDuringExecuteGetsScheduled() {
        Marshal m = newMarshal();
        boolean[] childRan = {false};
        Node child = ctx -> childRan[0] = true;
        Node parent = ctx -> ctx.addNode(NodeSpec.of(child).name("child").build());
        m.register(NodeSpec.of(parent).name("parent").build());

        RunReport r = m.run();

        assertThat(childRan[0]).isTrue();
        assertThat(r.statusOf(child)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void cycleIntroducingMutationFailsTheOriginNode() {
        Marshal m = newMarshal();
        AtomicReference<Node> aRef = new AtomicReference<>();
        Node b = ctx -> {};
        // 'a' runs and adds edge b->a; since a->b already exists (b depends on a),
        // that closes the cycle a->b->a and must be rejected, failing a.
        Node a = ctx -> ctx.addEdge(b, aRef.get());
        aRef.set(a);
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(Set.of(a)).name("b").build());

        RunReport r = m.run();

        assertThat(r.statusOf(a)).isEqualTo(Status.FAILED);
    }

    @Test
    void danglingReferenceMutationFailsOriginNodeAndRunCompletes() {
        Marshal m = newMarshal();
        Node x = ctx -> {};
        Node y = ctx -> {};
        // x and y are never registered; wiring an edge between them is a dangling reference.
        Node origin = ctx -> ctx.addEdge(x, y);
        Node independent = ctx -> {};
        m.register(NodeSpec.of(origin).name("origin").build());
        m.register(NodeSpec.of(independent).name("independent").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
        assertThat(r.statusOf(independent)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void failingNodeMutationsAreNotApplied() {
        Marshal m = newMarshal();
        Node child = ctx -> {};
        NodeSpec childSpec = NodeSpec.of(child).name("child").build();
        Node origin = ctx -> {
            ctx.addNode(childSpec);
            throw new RuntimeException("boom");
        };
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
        assertThat(r.statusOf(child)).isNull();
    }

    @Test
    void nodeAddedWithDanglingPredecessorFailsOriginNotCrashesRun() {
        Marshal m = newMarshal();
        Node neverRegistered = ctx -> {};
        Node child = ctx -> {};
        Node origin = ctx -> ctx.addNode(NodeSpec.of(child)
                .predecessors(Set.of(neverRegistered))
                .name("child")
                .build());
        Node independent = ctx -> {};
        m.register(NodeSpec.of(origin).name("origin").build());
        m.register(NodeSpec.of(independent).name("independent").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
        assertThat(r.statusOf(child)).isNull();
        assertThat(r.statusOf(independent)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void removeNodeMutationDeletesVictimBeforeItEverRuns() {
        Marshal m = newMarshal();
        boolean[] victimRan = {false};
        Node victim = ctx -> victimRan[0] = true;
        Node origin = ctx -> ctx.removeNode(victim);
        m.register(NodeSpec.of(origin).name("origin").build());
        // victim depends on origin, so it is still WAITING (not yet dispatched) when origin's
        // execute() buffers the removal.
        m.register(
                NodeSpec.of(victim).predecessors(Set.of(origin)).name("victim").build());

        RunReport r = m.run();

        assertThat(victimRan[0]).isFalse();
        assertThat(r.statusOf(origin)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(victim)).isNull(); // gone from the graph entirely, not just skipped
    }

    @Test
    void conflictMutationWiresAMutexBetweenAlreadyRegisteredNodes() {
        Marshal m = newMarshal();
        Node a = ctx -> {};
        Node b = ctx -> {};
        Node origin = ctx -> ctx.conflict(a, b);
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).name("b").build());
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(a)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(b)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void conflictMutationRejectsSelfConflictAndFailsOrigin() {
        Marshal m = newMarshal();
        AtomicReference<Node> originRef = new AtomicReference<>();
        Node origin = ctx -> ctx.conflict(originRef.get(), originRef.get());
        originRef.set(origin);
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
    }

    @Test
    void conflictMutationRejectsDanglingReferenceAndFailsOrigin() {
        Marshal m = newMarshal();
        Node a = ctx -> {};
        Node neverRegistered = ctx -> {};
        Node origin = ctx -> ctx.conflict(a, neverRegistered);
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
    }

    @Test
    void addNodeMutationDeclaringSelfConflictFailsOrigin() {
        Marshal m = newMarshal();
        AtomicReference<Node> childRef = new AtomicReference<>();
        Node origin = ctx -> {
            Node child = childRef.get();
            ctx.addNode(
                    NodeSpec.of(child).conflicts(Set.of(child)).name("child").build());
        };
        childRef.set(ctx -> {});
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
    }

    @Test
    void addNodeMutationDeclaringDanglingConflictFailsOrigin() {
        Marshal m = newMarshal();
        Node neverRegistered = ctx -> {};
        Node child = ctx -> {};
        Node origin = ctx -> ctx.addNode(NodeSpec.of(child)
                .conflicts(Set.of(neverRegistered))
                .name("child")
                .build());
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
        assertThat(r.statusOf(child)).isNull();
    }

    @Test
    void sameBatchCycleThroughNewlyAddedNodesLeavesThemUnreachable() {
        // Documented limitation (see Marshal.applyMutations javadoc): an AddEdge validated
        // within a batch only consults wouldIntroduceCycle() for endpoints that already exist in
        // the committed graph. Two brand-new nodes wired into a mutual cycle in the same batch
        // slip past that check, get applied, and then sit WAITING forever (each waits on the
        // other) -- reported as UNREACHABLE at quiescence, not silently dropped or crashed.
        Marshal m = newMarshal();
        Node x = ctx -> {};
        Node y = ctx -> {};
        Node origin = ctx -> {
            ctx.addNode(NodeSpec.of(x).name("x").build());
            ctx.addNode(NodeSpec.of(y).name("y").build());
            ctx.addEdge(x, y);
            ctx.addEdge(y, x);
        };
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.COMPLETED);
        assertThat(r.statusOf(x)).isEqualTo(Status.UNREACHABLE);
        assertThat(r.statusOf(y)).isEqualTo(Status.UNREACHABLE);
    }

    @Test
    void removeNodeMutationOfAGenuinelyRunningNodeIsRejectedAndOriginFails() throws Exception {
        // Uses Marshal.create() (real worker threads, unbounded IO lane) so runningNode is
        // genuinely Status.RUNNING -- not yet processed as Completed by the scheduler thread --
        // at the moment origin's completion is validated. Before the fix, applyMutations never
        // checks RUNNING for a RemoveNode, so g.removeNode(runningNode) would erase it from the
        // graph out from under its own in-flight worker: origin would wrongly complete, and
        // runningNode's later Completed would be silently dropped (its CPU permit -- if it were a
        // CPU node -- leaked, and it would vanish from the report instead of reaching COMPLETED).
        Marshal m = Marshal.create();
        CountDownLatch hold = new CountDownLatch(1);
        Node runningNode = ctx -> {
            try {
                // Never counted down: this bounded wait just keeps runningNode occupying its
                // worker thread (genuinely RUNNING) for longer than origin needs to complete and
                // have its removeNode(runningNode) batch validated.
                hold.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Node origin = ctx -> ctx.removeNode(runningNode);
        m.register(NodeSpec.of(runningNode).name("runningNode").build());
        m.register(NodeSpec.of(origin).name("origin").build());

        RunReport r = m.run();

        assertThat(r.statusOf(origin)).isEqualTo(Status.FAILED);
        assertThat(r.statusOf(runningNode)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void isCompletedReflectsGraphStateForKnownAndUnknownNodes() {
        Marshal m = newMarshal();
        List<Boolean> observed = new ArrayList<>();
        Node other = ctx -> {};
        Node neverRegistered = ctx -> {};
        Node checker = ctx -> {
            observed.add(ctx.isCompleted(other)); // other's predecessor edge guarantees it ran first
            observed.add(ctx.isCompleted(neverRegistered)); // null-safe: unknown nodes are not completed
        };
        m.register(NodeSpec.of(other).name("other").build());
        m.register(
                NodeSpec.of(checker).predecessors(Set.of(other)).name("checker").build());

        RunReport r = m.run();

        assertThat(r.statusOf(checker)).isEqualTo(Status.COMPLETED);
        assertThat(observed).containsExactly(true, false);
    }
}
