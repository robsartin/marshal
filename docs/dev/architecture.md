# marshal — developer architecture

This is the implementer's map of `marshal`: how the pieces fit together, why the
concurrency model is safe, and how to run the tests that keep it that way.

Full rationale and the decision table that drove these choices live in the design
spec: [`docs/superpowers/specs/2026-08-01-marshal-design.md`](../superpowers/specs/2026-08-01-marshal-design.md).
The five foundational ADRs (single-owner scheduler, node identity, dependency/conflict
modeling, invariants-first-class, plus the meta ADR) are in
[`docs/adr/`](../adr/) — start at [`docs/adr/README.md`](../adr/README.md).

## 1. The single-owner scheduler model

`Marshal` (`src/main/java/com/robsartin/marshal/Marshal.java`) owns one `GraphState`
instance and is the **only** thing that ever touches it. Everything else —
`Node.execute()` bodies, timeout callbacks — runs on other threads but never
reaches into `GraphState` directly. That single fact is what lets `GraphState`
have zero locks and zero concurrent collections (it's built entirely on
`IdentityHashMap`): there is never more than one thread inside it at a time.

Worker threads (from the IO or CPU lane) run a node's `execute(ExecutionContext ctx)`
and, when it returns, throws, or is interrupted, package the outcome plus any
buffered graph edits into an `Event.Completed` and hand it back to the owner
thread over a `BlockingQueue<Event>`. The owner thread never blocks on a worker;
it only ever blocks on `events.take()`.

```
   caller thread                    owner thread                 worker threads
   ─────────────                    ────────────                 ──────────────
   Marshal.run() ───────────────►  event loop (Marshal.run)
                                     │  take() from BlockingQueue<Event>
                                     ├─ Event.Completed(node, outcome, mutations)
                                     ├─ Event.TimedOut(node)
                                     │
                                     │  dispatch(): mark RUNNING, lane.execute(...) ───►  node.execute(ctx)
                                     │                                                    ctx buffers mutations
                                     │                                              ◄───  events.add(Completed(...))
                                     ▼
                                   report()
```

`Marshal.run()` itself runs on whatever thread calls it (there's no dedicated
scheduler thread spun up internally) — it *becomes* the owner thread for the
duration of the run by being the only thread that ever calls into `GraphState`
or drains the event queue. That's an implementation detail worth knowing: `run()`
blocks its caller until quiescence.

## 2. The event loop

The loop in `Marshal.run()` is intentionally small:

1. Dispatch every currently-ready node (`redispatch`), respecting priority,
   conflicts, and lane permits (see §4, `Selection`).
2. Block on `events.take()`.
3. On `Event.Completed`: cancel its timeout, apply its buffered mutations (or
   fail it — see §3), mark it terminal, free a CPU permit if it held one,
   `redispatch()` again.
4. On `Event.TimedOut`: mark the node `TIMED_OUT` (which cascades `SKIPPED` to
   its dependents), free a CPU permit if applicable, `redispatch()` again.
5. Repeat while any node is in flight (`inFlight > 0`).
6. Return a `RunReport` once `running` and `ready` are both empty — that's
   quiescence. Since only a running node's completion can ever change the
   graph, empty `running` means nothing will ever change again; there's no
   need for a separate "are we really done" check.

Nodes still `WAITING` or `READY` at quiescence (blocked on a failed/timed-out
predecessor, or genuinely unreachable — see §3's note on same-batch cycles) are
reported as `Status.UNREACHABLE`.

## 3. The mutation-commit protocol (atomic pre-validation)

A running node never mutates `GraphState` directly. Instead `Marshal` hands it a
`BufferingExecutionContext` (`ExecutionContext`), whose `addNode`/`removeNode`/
`addEdge`/`removeEdge`/`conflict`/`conflictGroup` calls just append `Mutation`
records to an in-memory list — the graph is untouched while the node runs. This
is what makes "a direct-looking mutation API" compatible with "only the owner
thread touches the graph": the node's own thread never needs write access to
shared state.

When the node's `Completed` event reaches the owner thread,
`Marshal.applyMutations(origin, batch)` runs in two passes:

1. **Validate the whole batch first**, simulating node membership as it walks
   the batch in order (an `AddNode` makes its node valid to reference *later in
   the same batch*; a `RemoveNode` makes it invalid). It rejects:
   - an `AddNode` whose declared predecessors/conflicts reference a node not
     present at that point in the batch, or that conflicts with itself;
   - an `AddEdge`/`AddConflict` referencing a node not present at that point
     ("dangling reference");
   - an `AddConflict` between a node and itself;
   - an `AddEdge` that would introduce a cycle, checked against the *current*
     committed graph for edges whose endpoints both already exist there.
2. **Only if validation passes**, apply every mutation in buffer order through
   `GraphState`'s audited mutators (each of which ends in `assert holds()`).

If validation fails, **nothing in the batch is applied** — the origin node is
marked `FAILED` instead, and its dependents skip per the normal failure-cascade
rule. This is the "atomic" part: a bad batch can't leave the graph half-edited.

**Known, documented limitation:** the cycle check in step 1 only consults
`GraphState.wouldIntroduceCycle` for edges whose endpoints already exist in the
graph *before this batch*. A cycle formed entirely between two-or-more nodes
added earlier in the *same* batch slips through (see the worked example in
`MarshalMutationTest.sameBatchCycleThroughNewlyAddedNodesLeavesThemUnreachable`).
This doesn't corrupt anything — `GraphState.invariant()` doesn't enforce
acyclicity — it just leaves the affected nodes `WAITING` forever, reported as
`UNREACHABLE` at quiescence. Full mid-batch cycle detection was deferred (see
spec §11, YAGNI).

## 4. The two execution lanes

`NodeSpec.kind()` (`ExecutionKind.IO` default, or `ExecutionKind.CPU`) tells the
scheduler which `Executor` to dispatch a ready node to:

- **IO lane** — meant for blocking, low-CPU work (network calls, file IO).
  `Marshal.create()` wires it to `Executors.newVirtualThreadPerTaskExecutor()`:
  effectively unbounded, so IO-bound nodes never queue behind each other for a
  thread.
- **CPU lane** — meant for CPU-bound work. Backed by a fixed thread pool, but
  additionally gated by an integer permit budget (`cpuPermits`, default
  `Runtime.availableProcessors()`) tracked by the scheduler itself, independent
  of the pool's own thread count. A CPU-lane node with no free permit just
  stays `READY` rather than being submitted to a saturated pool — that keeps
  priority ordering honest (see §5) instead of degrading to FIFO once the pool
  fills up.

Lane and permit admission is decided by the pure `Selection.select(...)`
function, which is why it's unit-testable with zero threads (see
`SelectionTest`): given `(graph, ready, running, freeCpuPermits, freeIoPermits)`
it deterministically returns the dispatch list, picking the highest-priority
ready node whose conflicts don't intersect `running` and whose lane has a free
permit, repeating until no such node remains.

## 5. The timeout model (interrupt + report)

Per-node timeouts (`NodeSpec.timeout()`) are enforced through the `Timeouts`
seam (`arm(node, budget, onExpiry)` / `cancel(node)`), not a hard-coded clock:

- **Production** (`Marshal.create()`) wires a real `ScheduledTimeouts`, backed
  by a single daemon `ScheduledExecutorService`.
- **Tests** use `support.ManualTimeouts`, a synchronous test double whose
  `expire(node)` fires the callback on demand — this is what makes
  `MarshalTimeoutTest` deterministic without sleeping.
- **The 3-arg `Marshal(ioLane, cpuLane, cpuPermits)` constructor** defaults to
  a private no-op `Timeouts` whose `arm` does nothing — useful when a caller
  (or a test) doesn't care about timeouts at all; declared timeouts are simply
  never enforced.

On dispatch, if the node has a timeout, the scheduler arms a watchdog. If it
fires before the node's worker posts `Completed`, the callback (a) posts
`Event.TimedOut(node)` to the event queue *before* (b) interrupting the
worker's thread — deliberately in that order, since the queue is FIFO: this
guarantees the owner processes `TimedOut` before any `Completed` the
interrupted worker goes on to post, so a worker that wakes and returns quickly
after being interrupted can't win a race and get recorded as `COMPLETED`
instead of `TIMED_OUT`. **Honoring `Thread.interrupt()` is part of the `Node`
contract** — the JVM cannot safely force-kill arbitrary synchronous code, so
this is the honest, "works if the node cooperates" option, not a hard kill.

If the node completes normally first, its watchdog is cancelled. A late
`Completed` for a node that's already been marked `TIMED_OUT` is ignored (the
event loop guards on `status == RUNNING` before processing it).

## 6. The Invariant discipline

Any class with a non-trivial representation invariant (currently just
`GraphState`) implements `Invariant`:

```java
public interface Invariant {
    void invariant(); // throws IllegalStateException (descriptive) if violated
}
```

Every mutator ends with `assert holds();` where `holds()` calls `invariant()`
and returns `true` (or throws). This only runs under `-ea` — which is why the
Gradle `test` task always passes `-ea` (see `build.gradle.kts`); without it the
checks compile to nothing. `GraphState.invariant()` checks, for every node:
conflict irreflexivity, the successors/predecessors transpose
(`b ∈ successors[a] ⟺ a ∈ predecessors[b]`), conflict symmetry, referential
integrity (no dangling successor/predecessor/conflict), and that
`remainingPreds[n]` still equals a fresh fold over live, non-completed
predecessors.

Records are exempt — they validate in their canonical constructor and can't
drift afterward. An ArchUnit rule (`ArchitectureTest.mutableStateClassesImplementInvariant`)
enforces that any class named `*State` implements `Invariant`, so this can't
silently regress.

### Running the jqwik model-based property test

`GraphStateModelPropertyTest` is the thing that actually *proves* the invariant
holds under arbitrary use, not just the hand-picked scenarios in the other unit
tests. It generates random sequences of graph commands (`ADD_NODE`, `ADD_EDGE`,
`REMOVE_EDGE`, `REMOVE_NODE`, `COMPLETE`) and, after every single step, replays
each command against both the real `GraphState` and a naive
`support.ReferenceGraphModel` oracle that recomputes successors/predecessors/
`remainingPreds` from scratch — then asserts `GraphState.invariant()` doesn't
throw *and* that the engine's denormalized indexes exactly match the oracle's
freshly-recomputed ones.

It's a normal JUnit Platform test (jqwik's JUnit 5 engine, wired into
`build.gradle.kts` via `includeEngines("junit-jupiter", "jqwik")`), so it runs
automatically with everything else:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test --tests "com.robsartin.marshal.GraphStateModelPropertyTest"
```

To run just this property with more tries while iterating on `GraphState` (the
default is `tries = 500`), temporarily bump the `@Property(tries = ...)` value
in the test itself — jqwik also supports a project-wide default via a
`jqwik.properties` file, but this repo doesn't use one; the per-test annotation
is the source of truth here.

The test also self-guards against its own biggest failure mode: a
non-capturing node lambda (`ctx -> {}`) gets cached and reused as a single JVM
instance by `LambdaMetafactory`, which would silently collapse every "new" node
in the property to the same object (since identity is the whole basis of node
equality — ADR-0013). The test avoids that by allocating a fresh anonymous
`Node` class instance per generated node, and additionally asserts — via
jqwik's `Statistics.label(...).coverage(...)` checks at the end of the
property — that edge/removal commands actually fired a meaningful number of
times across all tries, so a regression back to the shared-instance bug (or
anything else that makes those commands silently stop applying) fails the
build instead of passing vacuously.

## 7. Where to look next

| Question | File |
|---|---|
| What can a node do with the graph while it runs? | `ExecutionContext.java`, `BufferingExecutionContext.java` |
| What does a completed run report look like? | `RunReport.java`, `Status.java` |
| How is "ready to run" decided? | `Selection.java` |
| What does the ground-truth graph representation look like? | `GraphState.java` |
| Why these decisions and not others? | `docs/superpowers/specs/2026-08-01-marshal-design.md`, `docs/adr/` |
