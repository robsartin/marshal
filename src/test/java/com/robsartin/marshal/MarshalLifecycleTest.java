package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.marshal.support.InlineExecutor;
import com.robsartin.marshal.support.ManualTimeouts;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class MarshalLifecycleTest {

    @Test
    void createFactoryArgOrderMatchesConstructor() {
        Executor ioExec = new InlineExecutor();
        Executor cpuExec = new InlineExecutor();
        ManualTimeouts timeouts = new ManualTimeouts();

        Marshal m = Marshal.create(ioExec, cpuExec, 2, timeouts);
        Node n = ctx -> {};
        m.register(NodeSpec.of(n).name("n").build());

        RunReport r = m.run();

        assertThat(r.statusOf(n)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void runIsSingleUse() {
        Executor inline = new InlineExecutor();
        Marshal m = new Marshal(inline, inline, 2);
        Node n = ctx -> {};
        m.register(NodeSpec.of(n).name("n").build());

        m.run();

        assertThatThrownBy(m::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("single-use");
    }

    @Test
    void createOwnedExecutorsShutDownAfterRun() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Marshal m = new Marshal(exec, exec, 2, new ManualTimeouts(), true);
        Node n = ctx -> {};
        m.register(NodeSpec.of(n).name("n").build());

        m.run();

        assertThat(exec.isShutdown()).isTrue();
    }

    @Test
    void closeShutsDownOwnedExecutors() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Marshal m = new Marshal(exec, exec, 2, new ManualTimeouts(), true);

        m.close();

        assertThat(exec.isShutdown()).isTrue();
        // Idempotent: a second close() must not throw.
        assertThatCode(m::close).doesNotThrowAnyException();
    }

    @Test
    void callerSuppliedExecutorsNotShutDown() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Marshal m = new Marshal(exec, exec, 2, new ManualTimeouts());
            Node n = ctx -> {};
            m.register(NodeSpec.of(n).name("n").build());

            m.run();
            m.close();

            assertThat(exec.isShutdown()).isFalse();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void tryWithResourcesOnOwnedMarshal() {
        ExecutorService exec = Executors.newSingleThreadExecutor();

        assertThatCode(() -> {
                    try (Marshal m = new Marshal(exec, exec, 2, new ManualTimeouts(), true)) {
                        Node n = ctx -> {};
                        m.register(NodeSpec.of(n).name("n").build());
                        m.run();
                    }
                })
                .doesNotThrowAnyException();

        assertThat(exec.isShutdown()).isTrue();
    }
}
