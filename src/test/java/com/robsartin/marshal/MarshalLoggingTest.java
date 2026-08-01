package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/**
 * Covers run-id correlation: the explicit {@link ExecutionContext#runId()} path and the {@link
 * Marshal#currentRunId()} thread-local ambient path. See {@code
 * docs/backlog/logging-system-logger.md} for the design.
 */
class MarshalLoggingTest {
    private Marshal inlineMarshal() {
        Executor inline = new InlineExecutor();
        return new Marshal(inline, inline, 4);
    }

    @Test
    void runIdIsExposedAndStableWithinARun() {
        Marshal m = inlineMarshal();
        List<String> ids = new ArrayList<>();
        Node a = ctx -> {
            assertThat(ctx.runId()).isNotNull();
            ids.add(ctx.runId());
        };
        Node b = ctx -> ids.add(ctx.runId());
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(Set.of(a)).name("b").build());

        m.run();

        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
    }

    @Test
    void runIdDiffersAcrossRuns() {
        List<String> ids = new ArrayList<>();

        Marshal m1 = inlineMarshal();
        m1.register(NodeSpec.of((Node) ctx -> ids.add(ctx.runId())).build());
        m1.run();

        Marshal m2 = inlineMarshal();
        m2.register(NodeSpec.of((Node) ctx -> ids.add(ctx.runId())).build());
        m2.run();

        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }

    @Test
    void ambientRunIdMatchesContextInsideExecute() {
        List<String> captured = new CopyOnWriteArrayList<>();
        try (Marshal m = Marshal.create()) {
            Node a = ctx -> {
                captured.add(Marshal.currentRunId().orElse("MISSING"));
                captured.add(ctx.runId());
            };
            m.register(NodeSpec.of(a).build());

            m.run();
        }

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).isEqualTo(captured.get(1));
    }

    @Test
    void ambientRunIdIsClearedAfterRun() {
        // InlineExecutor runs node.execute() synchronously on this test thread, so this also
        // gives a best-effort check that a reused thread doesn't retain a stale run id: this
        // thread just carried a run id (set inside dispatch) and must have it cleared afterward.
        Marshal m = inlineMarshal();
        m.register(NodeSpec.of((Node) ctx -> {}).build());

        m.run();

        assertThat(Marshal.currentRunId()).isEmpty();
    }
}
