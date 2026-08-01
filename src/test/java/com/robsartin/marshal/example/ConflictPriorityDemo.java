package com.robsartin.marshal.example;

import static com.robsartin.marshal.ExecutionKind.CPU;
import static com.robsartin.marshal.ExecutionKind.IO;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.ExecutionKind;
import com.robsartin.marshal.Marshal;
import com.robsartin.marshal.Node;
import com.robsartin.marshal.NodeSpec;
import com.robsartin.marshal.RunReport;
import com.robsartin.marshal.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A runnable, self-verifying sample: builds a small graph of dummy "print" nodes with priorities
 * and a conflict group, executes it on {@link Marshal#create()} (real threads), and asserts the
 * observable behaviour — conflicting nodes never overlap, priority orders the ready set, and
 * independent work still runs in parallel. Run it as a JUnit test, or via {@code main}.
 *
 * <p>The graph (a stylised "data refresh"):
 *
 * <pre>
 *   setup ─┬─ migrate-db (pri 10) ─┐
 *          ├─ import-A  (pri 8)  ─┼─ reindex ─┐
 *          ├─ import-B  (pri 2)  ─┘           ├─ report
 *          └─ warm-cache(pri 6) ──────────────┘
 *   conflict group { migrate-db, import-A, import-B }  → at most one runs at a time
 * </pre>
 */
class ConflictPriorityDemo {

    private final Map<Node, String> names = Collections.synchronizedMap(new IdentityHashMap<>());
    private final List<String> groupStartOrder = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger groupRunning = new AtomicInteger();
    private final AtomicInteger groupMax = new AtomicInteger();
    private final AtomicInteger totalRunning = new AtomicInteger();
    private final AtomicInteger totalMax = new AtomicInteger();

    /** Nodes whose names are in this set are the mutually-exclusive conflict group. */
    private volatile Set<String> group = Set.of();

    /** A dummy node that just announces itself; the name is captured at construction. */
    private Node named(String name) {
        Node n = ctx -> {
            boolean inGroup = group.contains(name);
            int total = totalRunning.incrementAndGet();
            totalMax.accumulateAndGet(total, Math::max);
            if (inGroup) {
                groupStartOrder.add(name);
                groupMax.accumulateAndGet(groupRunning.incrementAndGet(), Math::max);
            }
            System.out.printf("  ▶ %-11s start   (run %s)%n", name, ctx.runId());
            sleep(120); // "work", so overlap (or its absence) is observable
            System.out.printf("      %-11s end%n", name);
            if (inGroup) {
                groupRunning.decrementAndGet();
            }
            totalRunning.decrementAndGet();
        };
        names.put(n, name);
        return n;
    }

    /** Create + register a node in one step; predecessors must already be registered. */
    private Node register(Marshal m, String name, int priority, ExecutionKind kind, Node... preds) {
        Node n = named(name);
        m.register(NodeSpec.of(n)
                .priority(priority)
                .kind(kind)
                .predecessors(Set.of(preds))
                .name(name)
                .build());
        return n;
    }

    @Test
    void conflictsAreExcludedAndPriorityOrdersTheReadySet() {
        try (Marshal m = Marshal.create()) {
            Node setup = register(m, "setup", 5, IO);
            // Once setup finishes these four become ready together. The three conflict-group
            // members run strictly one-at-a-time in priority order; warm-cache is independent
            // and runs in parallel with them.
            Node migrate = register(m, "migrate-db", 10, IO, setup);
            Node importA = register(m, "import-A", 8, IO, setup);
            Node importB = register(m, "import-B", 2, IO, setup);
            Node warmCache = register(m, "warm-cache", 6, IO, setup);
            Node reindex = register(m, "reindex", 5, CPU, importA, importB);
            Node report = register(m, "report", 0, IO, migrate, reindex, warmCache);

            group = Set.of("migrate-db", "import-A", "import-B");
            m.conflictGroup(Set.of(migrate, importA, importB));

            RunReport rr = m.run();

            System.out.println("\n--- run report ---");
            rr.statuses().forEach((n, s) -> System.out.printf("  %-11s %s%n", names.get(n), s));

            // Every node ran to completion.
            assertThat(rr.statuses().values()).allMatch(s -> s == Status.COMPLETED);
            // The conflict group NEVER had two members running at once.
            assertThat(groupMax.get()).isEqualTo(1);
            // Priority ordered the mutually-exclusive group: 10 > 8 > 2.
            assertThat(groupStartOrder).containsExactly("migrate-db", "import-A", "import-B");
            // Independent work (warm-cache) genuinely ran in parallel with the group.
            assertThat(totalMax.get()).isGreaterThanOrEqualTo(2);
        }
    }

    /** Standalone entry point — same scenario, for running the sample by hand. */
    public static void main(String[] args) {
        new ConflictPriorityDemo().conflictsAreExcludedAndPriorityOrdersTheReadySet();
        System.out.println("\nOK: conflicts excluded, priority ordered, all completed.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
