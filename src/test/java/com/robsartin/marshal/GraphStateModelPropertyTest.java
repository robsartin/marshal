package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.ReferenceGraphModel;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Model-based property test: drives random command sequences through both {@link GraphState}
 * and {@link ReferenceGraphModel} (a naive oracle that recomputes successors/predecessors/
 * remainingPreds from ground truth on demand), asserting after every step that (a)
 * {@code GraphState.invariant()} does not throw and (b) the engine's denormalized indexes
 * equal the oracle's freshly-recomputed ones.
 *
 * <p>jqwik 1.9.1 (pinned in this build) removed the old {@code net.jqwik.api.stateful}
 * package ({@code ActionSequence}/{@code Action}/{@code Arbitraries.sequences(...)}), so this
 * test does not use that stateful-actions API. Instead it uses a plain {@code @Property} over
 * a generated {@code List<Command>}, replayed against one fresh {@link GraphState} and one
 * fresh {@link ReferenceGraphModel} per try — the same shadow-verification goal, achieved with
 * jqwik's current API.
 *
 * <p><b>Distinct node instances:</b> each {@code ADD_NODE} command must produce a genuinely new
 * {@link Node}. A non-capturing lambda such as {@code ctx -> {}} has no per-instance state, so
 * the JVM's {@code LambdaMetafactory} caches and reuses a single shared instance for every
 * evaluation of that expression — every "new" node would in fact be the same object, collapsing
 * the whole graph to one node ({@code GraphState.addNode} is idempotent on identity) and making
 * every edge command a same-node no-op. This test instead allocates a fresh anonymous class
 * instance per node, which the JVM cannot fold into a singleton.
 *
 * <p><b>Self-guarding coverage:</b> a regression back to the shared-instance bug (or any other
 * change that makes edge/removal commands silently stop applying) is caught automatically via
 * jqwik {@link Statistics} coverage checks at the bottom of the property, not just by eyeballing
 * try counts — see {@code assertCoverage()}.
 */
class GraphStateModelPropertyTest {

    private enum Kind {
        ADD_NODE,
        ADD_EDGE,
        REMOVE_EDGE,
        REMOVE_NODE,
        COMPLETE
    }

    private record Command(Kind kind, int i1, int i2) {}

    @Property(tries = 500)
    void engineMatchesOracleAfterEveryStep(@ForAll("commands") List<Command> commands) {
        GraphState engine = new GraphState();
        ReferenceGraphModel oracle = new ReferenceGraphModel();
        List<Node> created = new ArrayList<>();

        for (Command cmd : commands) {
            apply(cmd, engine, oracle, created);
            check(engine, oracle, created);
        }

        assertCoverage();
    }

    @Provide
    Arbitrary<List<Command>> commands() {
        Arbitrary<Kind> kinds = Arbitraries.of(Kind.values());
        Arbitrary<Integer> indices = Arbitraries.integers().between(0, 20);
        return Combinators.combine(kinds, indices, indices).as(Command::new).list().ofMaxSize(40);
    }

    private static void apply(Command cmd, GraphState engine, ReferenceGraphModel oracle, List<Node> created) {
        switch (cmd.kind()) {
            case ADD_NODE -> {
                // Distinct instance per node -- see class javadoc. A non-capturing lambda would
                // be cached as a single shared instance by the JVM and silently collapse the
                // whole graph to one node.
                Node n = new Node() {
                    @Override
                    public void execute(ExecutionContext ctx) {}
                };
                created.add(n);
                engine.addNode(NodeSpec.of(n).build());
                oracle.addNode(n);
            }
            case ADD_EDGE -> {
                boolean applied = false;
                if (created.size() >= 2) {
                    Node a = created.get(cmd.i1() % created.size());
                    Node b = created.get(cmd.i2() % created.size());
                    if (a != b
                            && engine.contains(a)
                            && engine.contains(b)
                            && !engine.wouldIntroduceCycle(a, b)) {
                        engine.addEdge(a, b);
                        oracle.addEdge(a, b);
                        applied = true;
                    }
                }
                Statistics.label("edgeApplied").collect(applied);
            }
            case REMOVE_EDGE -> {
                boolean applied = false;
                if (created.size() >= 2) {
                    Node a = created.get(cmd.i1() % created.size());
                    Node b = created.get(cmd.i2() % created.size());
                    if (a != b
                            && engine.contains(a)
                            && engine.contains(b)
                            && engine.successors(a).contains(b)) {
                        engine.removeEdge(a, b);
                        oracle.removeEdge(a, b);
                        applied = true;
                    }
                }
                Statistics.label("edgeRemoved").collect(applied);
            }
            case REMOVE_NODE -> {
                boolean applied = false;
                if (!created.isEmpty()) {
                    Node n = created.get(cmd.i1() % created.size());
                    if (engine.contains(n)) {
                        engine.removeNode(n);
                        oracle.removeNode(n);
                        created.remove(n);
                        applied = true;
                    }
                }
                Statistics.label("nodeRemoved").collect(applied);
            }
            case COMPLETE -> {
                if (created.isEmpty()) return;
                Node n = created.get(cmd.i1() % created.size());
                if (engine.status(n) == Status.WAITING && engine.remainingPreds(n) == 0) {
                    engine.markReady(n);
                    engine.markRunning(n);
                    engine.markCompleted(n);
                    oracle.setStatus(n, Status.COMPLETED);
                }
            }
        }
    }

    private static void check(GraphState engine, ReferenceGraphModel oracle, List<Node> created) {
        engine.invariant(); // never throws
        for (Node n : created) {
            if (!engine.contains(n)) continue;
            assertThat(engine.successors(n)).isEqualTo(oracle.successors(n));
            assertThat(engine.predecessors(n)).isEqualTo(oracle.predecessors(n));
            assertThat(engine.remainingPreds(n)).isEqualTo(oracle.remainingPreds(n));
        }
    }

    /**
     * Fails the property if, across the whole run (all tries), edge or node removal commands
     * essentially never fire. This is the regression guard for the shared-lambda-instance bug:
     * under that bug every "distinct" node was actually the same object, so {@code a != b} was
     * always false and {@code edgeApplied} would sit at 0% no matter how many tries ran.
     */
    private static void assertCoverage() {
        Statistics.label("edgeApplied")
                .coverage(coverage -> coverage.check(true).percentage(p -> {
                    assertThat(p).isGreaterThan(10.0);
                }));
        Statistics.label("edgeRemoved")
                .coverage(coverage -> coverage.check(true).count(c -> {
                    assertThat(c).isGreaterThan(0);
                }));
        Statistics.label("nodeRemoved")
                .coverage(coverage -> coverage.check(true).count(c -> {
                    assertThat(c).isGreaterThan(0);
                }));
    }
}
