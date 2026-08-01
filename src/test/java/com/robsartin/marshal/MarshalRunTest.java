package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class MarshalRunTest {
    private Marshal newMarshal() {
        Executor inline = new InlineExecutor();
        return new Marshal(inline, inline, 4);
    }

    @Test
    void runsDependencyChainInOrderAndReportsCompleted() {
        Marshal m = newMarshal();
        List<String> log = new java.util.ArrayList<>();
        Node a = ctx -> log.add("a");
        Node b = ctx -> log.add("b");
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(java.util.Set.of(a)).name("b").build());

        RunReport report = m.run();

        assertThat(log).containsExactly("a", "b");
        assertThat(report.statusOf(a)).isEqualTo(Status.COMPLETED);
        assertThat(report.statusOf(b)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void failedNodeSkipsItsDependentsButNotIndependentWork() {
        Marshal m = newMarshal();
        Node boom = ctx -> {
            throw new RuntimeException("boom");
        };
        Node dependent = ctx -> {};
        Node independent = ctx -> {};
        m.register(NodeSpec.of(boom).name("boom").build());
        m.register(NodeSpec.of(dependent).predecessors(java.util.Set.of(boom)).build());
        m.register(NodeSpec.of(independent).build());

        RunReport report = m.run();

        assertThat(report.statusOf(boom)).isEqualTo(Status.FAILED);
        assertThat(report.statusOf(dependent)).isEqualTo(Status.SKIPPED);
        assertThat(report.statusOf(independent)).isEqualTo(Status.COMPLETED);
    }
}
