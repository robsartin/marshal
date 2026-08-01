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
 */
class GraphStateModelPropertyTest {

    private enum Kind {
        ADD_NODE,
        ADD_EDGE,
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
                Node n = ctx -> {};
                created.add(n);
                engine.addNode(NodeSpec.of(n).build());
                oracle.addNode(n);
            }
            case ADD_EDGE -> {
                if (created.size() < 2) return;
                Node a = created.get(cmd.i1() % created.size());
                Node b = created.get(cmd.i2() % created.size());
                if (a != b && !engine.wouldIntroduceCycle(a, b)) {
                    engine.addEdge(a, b);
                    oracle.addEdge(a, b);
                }
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
}
