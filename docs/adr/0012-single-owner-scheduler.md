---
status: Accepted
date: "2026-08-01"
topic: single-owner-scheduler
tags: [marshal, concurrency, architecture]
supersedes: []
related: [node-identity-by-reference, dependency-and-conflict-modeling, invariants-first-class]
---
# 12. Execute the graph with a single-owner scheduler and worker pool

## Context

marshal executes a graph whose nodes carry predecessors, mutual-exclusion conflicts,
timeouts, and priority, while many nodes run at once and a running node may mutate the
graph. That specific combination — live mutation *plus* conflicts *plus* priority *plus*
concurrency — is exactly what punishes designs built on shared mutable state: every
"can this node start while that one edits an edge?" question becomes a locking problem.

## Decision

One **scheduler thread owns all mutable graph state** (nodes, edges, conflicts, status,
the ready queue, the running set) and runs an event loop over a `BlockingQueue`. Worker
threads do exactly one thing: run a node's synchronous `execute` and post a `Completed`
event back. Because a single thread touches the state, there are no locks and no
concurrent collections.

- **Two execution lanes:** an IO lane backed by virtual threads (effectively unbounded,
  blocking-friendly) and a CPU lane backed by a cores-sized pool gated by a semaphore.
  Node lane is chosen by `ExecutionKind` (default `IO`).
- **Mutations commit at completion:** a running node's edits are buffered on its
  `ExecutionContext` and applied by the owner thread when the node finishes, through the
  invariant-preserving mutators. Nodes are fire-and-forget — they never block on their own
  additions.
- **Determinism seams:** the `Executor` (per lane) and a `Clock` are injected, so an inline
  executor + fake clock make runs fully deterministic in tests. The admission decision is a
  pure function of a state snapshot.

## Alternatives considered

- **Shared state + locks** — the lock choreography around conflicts + priority + live
  mutation together is where the bugs live; deadlock surface.
- **`CompletableFuture` dependency graph** — elegant for static DAGs, but priority,
  pairwise conflicts, and live mutation all fight future composition.
- **Work-stealing `ForkJoinPool` + in-degree counters** — great throughput, but work
  stealing reorders work so priority is weak, and mutating counters live is racy.
- **Tick/round scheduler** — same single-owner spirit, batched into rounds; slightly more
  latency than the event loop for no benefit here.

## Consequences

- Every concurrency question reduces to single-threaded reasoning; correctness is
  unit-testable without a concurrency proof.
- Workers must marshal results back through the queue; there is a small event-loop hop
  between a node finishing and its successors starting.
- The owner thread is a throughput ceiling for *scheduling* work, not for node execution
  (which fans out across the lanes). Fine for the intended dozens-to-thousands-of-nodes
  scale.
