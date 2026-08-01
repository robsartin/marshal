package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.Set;
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
}
