# marshal — Design

**Status:** Draft for review
**Date:** 2026-08-01
**Repo:** `marshal` · group/package `com.robsartin.marshal` · JVM 21+ (Java)

---

## 1. Purpose

`marshal` is a small, framework-free JVM library that executes a graph of nodes
subject to four kinds of requirement:

- **Predecessors** — nodes that must complete before this node may run.
- **Conflicts** — nodes that must not run at the same time as this node.
- **Timeout** — a maximum wall-clock time to allow a node to run.
- **Priority** — when there is a choice of what to run next, prefer higher priority.

Execution begins with every node that has no predecessors. Multiple nodes run at
once on multiple threads; each node's work is a **synchronous** `execute` call.
The execution graph is **mutable**: a running node may add or remove nodes, and
change dependencies and conflicts.

The library uses **no Spring code** but is designed to be consumed cleanly from a
Spring Boot application (wrap it in a `@Bean`, hand it an `Executor`).

## 2. Constraints and decisions (the "why")

These were settled during brainstorming and drive the rest of the design.

| Decision | Choice | Rationale |
|---|---|---|
| Intent | Build one design (five architectures used only as a decision aid) | Converge, don't survey. |
| Workload | Mixed I/O + CPU per node | Drives the two-lane executor. |
| Concurrency model | **Single-owner scheduler thread + worker pool (actor/event-loop)** | Live mutation + conflicts + priority + concurrency is exactly the combination that punishes shared-mutable-state designs. One owner turns every concurrency question into a single-threaded question. |
| Mutation | **Direct-looking API, committed at completion** | `ctx` buffers edits; they commit atomically when the node finishes. Reconciles "direct mutation API" with single-owner state. |
| Within-node wait | **Fire-and-forget only** | A node never blocks on its own additions inside `execute`. Keeps the owner model clean. |
| `Node` signature | `void execute(ExecutionContext ctx)` | Still a single-abstract-method functional interface; the context is how a node reads state and mutates the graph. |
| Node identity | **Object reference** (`IdentityHashMap`) | No separate `NodeId`. One `Node` instance = one graph node. Lambdas have no value identity, so we key by reference deliberately. |
| Node metadata | Lives in `NodeSpec`, **not** on `Node` | `Node` stays pure behavior; scheduling requirements are separate and per-registration. |
| Failure policy | **Skip transitive dependents, continue independent branches** | Most useful default for a general library; run returns a per-node status report. |
| Timeout policy | **Interrupt + report; honoring interruption is part of the `Node` contract** | The JVM cannot safely force-kill arbitrary synchronous code; this is the honest, reliable-for-compliant-nodes option. |
| Join model | **AND-join** (all predecessors) in v1, with a strategy seam | `ANY`/`N_OF(k)` can be added later as monotonic counter thresholds without touching the engine. Arbitrary predicates rejected (they wreck complexity and testability). |
| Conflict model | **`Map<Node, Set<Node>>`**, `conflict(a,b)` and `conflictGroup(set)` | A "set of conflicting nodes" is a mutex/clique; separate pairs give non-transitive (independent-set) semantics; one shared group gives clique semantics. No separate "resource key" concept in the API. |
| Execution kind | **Closed enum `ExecutionKind { IO, CPU }`** on `NodeSpec`, default `IO` | A marker interface fights the lambda (intersection casts) and contradicts "metadata outside `Node`." Migrate enum → sealed interface only if a kind must carry data. |
| Language | Java 21 | Records, virtual threads, pattern matching, sealed types — no extra language for a Spring-consumable library. |

## 3. Public API surface

```java
@FunctionalInterface
public interface Node {
    void execute(ExecutionContext ctx);
}

public enum ExecutionKind { IO, CPU }

public record NodeSpec(
    Node behavior,
    int priority,               // higher = scheduled first among eligible nodes
    Duration timeout,           // null / ZERO = no timeout
    ExecutionKind kind,         // default IO
    Set<Node> predecessors,
    Set<Node> conflicts,
    String name                 // optional; for the status report / logs
) {}

public interface ExecutionContext {
    // read-only snapshot the node may consult
    boolean isCompleted(Node n);
    // buffered mutations (fire-and-forget; commit at completion)
    void addNode(NodeSpec spec);
    void removeNode(Node n);
    void addEdge(Node predecessor, Node successor);
    void removeEdge(Node predecessor, Node successor);
    void conflict(Node a, Node b);
    void conflictGroup(Set<Node> nodes);
}

public final class Marshal {
    Marshal(Executor ioLane, Executor cpuLane, int cpuPermits, Clock clock);
    // builder / registration API
    Node register(NodeSpec spec);
    void conflict(Node a, Node b);
    void conflictGroup(Set<Node> nodes);
    RunReport run();            // blocks until quiescence, returns per-node status
}

public interface Invariant {
    /** Throws IllegalStateException (descriptive) if the representation invariant is violated. */
    void invariant();
}
```

`RunReport` maps each node to a terminal `Status`:
`COMPLETED | FAILED | TIMED_OUT | SKIPPED | UNREACHABLE`, with the failure cause
where relevant.

## 4. State representation

**Ground truth** is only three things:

- **D** — dependency edges (`a → b` means a must precede b)
- **C** — conflict edges (symmetric, irreflexive)
- **`status[n]`** — each node's lifecycle state

Everything else is a **maintained index or fold** over those (all `IdentityHashMap`):

| Field | Really is |
|---|---|
| `predecessors`, `successors` | relation **D**, indexed both directions (transposes) |
| `remainingPreds[n]` | fold: `count(p in predecessors[n] where status[p] != COMPLETED)` |
| `conflicts` | **C** as an adjacency index; also serves as the wake-list |
| `running` | `{ n : status[n] == RUNNING }` |
| `ready` | priority queue of `{ n : status[n] == READY }` |

Because these are indexes over D/C/status, they cannot *disagree with each other* —
only with their source — which shrinks the consistency obligation.

Note: a declared clique of size *k* costs *k(k−1)* directed conflict entries.
Fine for normal (small) conflict groups; if a very large group ever appears, that
single group is the one case to special-case with a shared sentinel. Not built now.

## 5. Concurrency and execution model

```
   ┌─────────────────────────────────────────────┐
   │  Scheduler thread  (sole owner of all state) │
   │   event loop: take() from BlockingQueue      │
   │     ├─ Completed(node, outcome, mutations)   │
   │     ├─ TimedOut(node)                         │
   │     └─ Stop                                   │
   └───────────────▲──────────────────┬───────────┘
       post events  │                  │ dispatch to lane (IO / CPU)
                    │                  ▼
   ┌────────────────┴──────────────────────────────┐
   │  Workers: run node.execute(ctx), then post     │
   │  Completed(node, outcome, ctx.drainMutations())│
   └────────────────────────────────────────────────┘
```

- The **scheduler thread is the only thread that touches graph state.** Workers
  run a lambda and report back. No locks, no concurrent collections, no torn state.
- **Two execution lanes:** IO lane = `newVirtualThreadPerTaskExecutor()` (effectively
  unbounded, blocking-friendly); CPU lane = a cores-sized pool gated by a semaphore
  of `cpuPermits` (default = available processors).
- The library imports nothing from Spring. A Spring app wraps `Marshal` in a `@Bean`
  and supplies the lane executors.

### 5.1 Selection function (pure)

After every completion/mutation the loop runs a pure function over
`(ready, running, conflicts, lanePermits)`:

> repeatedly pick the highest-priority ready node whose `conflicts` do not
> intersect `running` **and** whose lane has a free permit; move it to `running`
> and dispatch; stop when no such node remains.

Purity makes conflict/priority/lane admission unit-testable with zero threads.
A CPU node with no free permit simply waits in `ready`, so priority stays honest
instead of nodes piling up unexecuted inside a saturated pool.

### 5.2 Mutation-commit protocol

1. On dispatch of node **N**, build an `ExecutionContext` whose mutating methods
   **append to a per-execution buffer** (they do not touch the graph) and expose a
   read-only snapshot.
2. The worker runs `N.execute(ctx)`; mutations accumulate. N never observes a
   half-mutated graph — only its snapshot plus its own pending intent.
3. On return/throw/interrupt, the worker posts
   `Completed(N, outcome, ctx.drainMutations())`.
4. The scheduler applies the buffered mutations **through the audited mutators**
   (each ending in `assert holds()`), then processes completion: mark N terminal,
   relax successors' `remainingPreds`, propagate `SKIPPED` if N failed, remove N
   from `running`, re-examine exactly `conflicts[N]` (the wake-list), then re-run
   selection.

Mutations thus **commit atomically at completion, single-threaded**, via the
invariant-preserving path. Fire-and-forget is what makes this clean.

**Bad mutation batch → N fails.** If the batch would violate an invariant —
a dangling reference, or an added edge introducing a **cycle** (a deadlock we must
reject; cycle check is an incremental reachability test in `addEdge`) — the batch is
rejected atomically and N is marked `FAILED`, so its dependents skip.

### 5.3 Timeouts

On dispatch, if N has a timeout, register a deadline with a watchdog
(`ScheduledExecutorService` / `DelayQueue`). If it fires first: interrupt N's worker
thread and post `TimedOut(N)`; the scheduler marks it `TIMED_OUT` and treats it as a
failure for skip purposes. If N completes first, cancel the watchdog. Late
`Completed` for an already-terminal node is ignored (guard on `status == RUNNING`).

### 5.4 Termination

The run ends at **quiescence**: `running` empty and `ready` empty. Since only a
running `execute` can mutate the graph, nothing running means nothing will ever
change — quiescence is final. Nodes still `WAITING` (blocked on a failed
predecessor, or stranded) are reported `SKIPPED` / `UNREACHABLE`.

## 6. Complexity

Let `n` = nodes, `e` = dependency edges, `c` = conflict edges.

| Concern | Structure | Total over a run |
|---|---|---|
| Dependency readiness | `remainingPreds` counter + adjacency | **O(n + e)** |
| Priority choice | binary heap `ready` | **O(n log n)** |
| Conflict admission | `conflicts` as adjacency + wake-list | **O(c)** amortized (no rescans) |
| Live mutation | hash-map adjacency + counter adjust | **O(1)** per add/remove |

**Total: O(n log n + e + c)** — linear in graph size plus a log factor for priority.
If priority is a small bounded integer, bucket queues drop the log to
**O(n + e + c + P)** (P = priority range) — deferred until profiling asks for it.

## 7. Correctness discipline (repo-local decision)

Mutable classes with a non-trivial representation invariant implement **`Invariant`**
(`void invariant()`), asserted after every mutation under `-ea`:

```java
private boolean holds() { invariant(); return true; }   // throws on violation
// at the end of every mutator:
assert holds();
```

- **Immutable value types (records) are exempt** — they validate in their canonical
  constructor and cannot drift.
- The invariant lists every cross-structure equality: transpose
  (`b ∈ successors[a] ⟺ a ∈ predecessors[b]`), conflict symmetry + irreflexivity,
  the `remainingPreds` fold, the `running` index, and referential integrity.
- **jqwik model-based tests** drive random operation sequences (add/remove node,
  add/remove edge, complete, fail, conflict, conflictGroup) and after every step
  assert `invariant()` **and** equality against a naive **reference oracle** that
  recomputes indexes from D/C/status. This is what *guarantees* the denormalized
  data stays consistent.
- An **ArchUnit** test enforces "mutable classes in `core` implement `Invariant`."

Tooling added (test scope only, nothing in the runtime or for consumers):
**jqwik** (property + stateful/model-based testing) and **ArchUnit**.

## 8. Testability

Two constructor seams — an **`Executor`** (per lane) and a **`Clock`** — make runs
deterministic:

- Inject a **synchronous inline executor** → nodes run one at a time on the
  scheduler thread in priority order. Fully deterministic.
- Inject a **fake clock** → timeouts fire exactly when the test says.
- The **selection function is pure** → test conflict/priority/lane admission with no
  threads at all.

Production injects `newVirtualThreadPerTaskExecutor()` (IO) + a cores-sized pool
(CPU) and a real clock.

## 9. ADRs

Scaffolded with the `adr-toolkit` plugin at project init:

- **0001** — Record architecture decisions (meta)
- **0002** — Single-owner (actor) scheduler + worker pool; two execution lanes
- **0003** — Node identity by object reference; scheduling metadata outside `Node`
- **0004** — Dependency & conflict modeling (`Map<Node,Set<Node>>`, AND-join + seam)
- **0005** — Representation invariants are first-class and machine-checked (repo-local
  for now; may be promoted to a personal standard once proven)

## 10. Quality gates

- ArchUnit architecture tests (incl. the `Invariant` rule)
- jqwik property / model-based tests with the reference oracle
- Unit tests via inline executor + fake clock; TDD (red → green → refactor)
- CI: `ruff`-equivalent for Java is `spotless`/format check + `checkstyle` (or
  `error-prone`), tests run with `-ea`, coverage gate (line > 80% / branch > 65%)
- Dev + user documentation

## 11. Explicitly deferred (YAGNI)

- `ANY` / `N_OF(k)` join policies (seam only)
- Named/shared conflict resources by string key
- Sealed-interface `ExecutionKind` carrying data
- Bucket/radix priority queue
- Blocking-within-`execute` (nested execution)
- Large-clique conflict sentinel optimization
- Promoting the invariant discipline to a cross-project standard
