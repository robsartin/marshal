---
status: Accepted
date: "2026-08-01"
topic: dependency-and-conflict-modeling
tags: [marshal, data-model, algorithms]
supersedes: []
related: [single-owner-scheduler, node-identity-by-reference, invariants-first-class]
---
# 14. Model dependencies and conflicts as node adjacency; AND-join with a seam

## Context

Nodes declare predecessors (ordering) and conflicts (mutual exclusion), the graph is
mutable at runtime, and scheduling must respect priority. The representation has to make
readiness, conflict admission, and live edits all cheap, and it must not let derived data
drift.

## Decision

- **Dependencies and conflicts are both `Map<Node, Set<Node>>`.** The dependency relation
  is kept as dual indexes — `predecessors` and `successors` (transposes of one relation) —
  plus a `remainingPreds` counter for O(1) readiness. Conflicts are a symmetric adjacency
  map that *also* serves as the wake-list: when a node finishes, exactly its conflict
  neighbours are re-examined.
- **Conflicts are declared as node sets:** `conflict(a, b)` (a pair) and
  `conflictGroup(Set<Node>)` (a clique). Declaring two pairs `{A,B}` and `{B,C}`
  separately yields non-transitive semantics (A and C may co-run); one group `{A,B,C}`
  yields full mutual exclusion. No separate "resource key" concept is exposed — the set
  *is* the unit of exclusion.
- **AND-join only in v1:** a node runs when *all* predecessors have completed. The readiness
  check is a strategy seam so `ANY` / `N_OF(k)` (both monotonic counter thresholds) can be
  added later without touching the engine. Arbitrary readiness predicates are rejected —
  they destroy the complexity guarantee and the testability.
- **Ground truth vs indexes:** the only sources of truth are the dependency edges, the
  conflict edges, and node status; `successors`/`predecessors`/`remainingPreds`/`running`/
  `ready` are maintained indexes over them (see [ADR 15](0015-invariants-first-class.md)).

Overall run cost is **O(n log n + e + c)** — linear in the graph plus a log factor for
priority ordering.

## Alternatives considered

- **Edge lists** — simplest, but O(n) neighbour lookups and poor under mutation.
- **In-degree counters only (no predecessor sets)** — fast, but the counter is fragile to
  keep correct when edges are added/removed live; keeping the predecessor set as truth fixes
  that.
- **A named resource-key primitive** — subsumes node-sets and can compact huge cliques, but
  adds a concept the API doesn't need; node-sets already express both clique and
  non-transitive conflict. Deferred until a real need (e.g. dynamic membership by name).
- **Arbitrary `Predicate<GraphView>` readiness** — maximally general, but non-monotonic and
  untestable at scale.

## Consequences

- Adding/removing a node or edge is O(1); the run stays near-linear.
- A declared clique of size *k* stores *k(k−1)* directed conflict entries — negligible for
  the small conflict groups seen in practice; a very large group would be the one case to
  special-case later.
- Only AND-join ships now; richer joins are a documented, low-risk extension.
