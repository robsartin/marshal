---
status: Accepted
date: "2026-08-01"
topic: invariants-first-class
tags: [marshal, correctness, testing]
supersedes: []
related: [use-test-driven-development, jvm-quality-and-tests, dependency-and-conflict-modeling, single-owner-scheduler]
---
# 15. Make representation invariants first-class and machine-checked

## Context

The scheduler keeps several denormalized indexes — `successors`, `predecessors`,
`remainingPreds`, `running`, `ready` — over three sources of truth: the dependency edges,
the conflict edges, and node status (see [ADR 14](0014-dependency-and-conflict-modeling.md)).
Denormalized data that silently drifts from its source is precisely where an engine like
this rots. We want drift to be caught mechanically, not hoped away.

## Decision

- **Mutable classes with a non-trivial representation invariant implement `Invariant`** — a
  single `void invariant()` that throws `IllegalStateException` (with a descriptive message)
  when the invariant is violated. It asserts every cross-structure equality: the
  predecessor/successor transpose, conflict symmetry and irreflexivity, the `remainingPreds`
  fold, the `running` index, and referential integrity.
- **Asserted after every mutation under `-ea`** via the idiom `assert holds();` (where
  `holds()` runs `invariant()` and returns `true`), so the check is free in production and
  active in tests/CI.
- **Shadowed by jqwik model-based property tests:** random sequences of operations
  (add/remove node, add/remove edge, complete, fail, conflict, conflictGroup) run through
  both the real class and a naive **reference oracle** that recomputes every index from the
  ground truth; after each step the test asserts `invariant()` holds *and* the fast indexes
  equal the oracle. This is what *guarantees* the denormalized data.
- **Immutable value types (records) are exempt** — they validate once in their canonical
  constructor and cannot drift.
- **An ArchUnit test enforces the rule:** mutable `*State` classes in the core must implement
  `Invariant`.
- **Testing shape for this library:** marshal has no external dependencies, so the JVM
  baseline's Testcontainers/integration layer ([ADR 10](0010-jvm-quality-and-tests.md)) does
  not apply here; its role is filled by jqwik property/model-based tests plus ArchUnit, on
  top of the standard unit tests.

This is a repo-local decision for now; it may be promoted to a cross-project standard once
proven on marshal.

## Alternatives considered

- **Scattered ad-hoc consistency checks** — easy to forget at the mutator that matters;
  no single source of the invariant.
- **AOP / bytecode weaving to auto-invoke `invariant()` after every public method** —
  heavyweight and exactly the framework magic this library avoids; the one-line
  `assert holds();` is clearer and free.
- **Trust code review and unit tests** — humans miss index-drift bugs that a shadow oracle
  finds in a handful of generated sequences.

## Consequences

- Index drift is caught mechanically, on every test run, at the mutation that caused it.
- Adds jqwik and ArchUnit as **test-scope** tools only — nothing enters the library runtime
  or its consumers.
- A modest authoring cost per mutable class (write the invariant, keep it honest) buys a
  correctness guarantee for the whole engine.
- Tests must run with `-ea`; the CI `test` task sets it.
