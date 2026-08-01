# Architecture Decision Records

## Universal

- [1. Record architecture decisions with ADRs](0001-record-architecture-decisions.md) — _Accepted_
  Architecturally significant decisions — choices that shape structure, dependencies, interfaces, or the way the team works — need a durable record.
  Related: [6. Keep developer and user documentation current](0006-keep-documentation-current.md)
- [2. Develop with Test-Driven Development](0002-use-test-driven-development.md) — _Accepted_
  We want a fast feedback loop, a regression safety net, executable documentation of behavior, and the freedom to refactor without fear.
- [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md) — _Accepted_
  We want `main` to stay releasable at all times, changes to be reviewable in coherent units, and history to be legible.
  Related: [4. Use the Mikado Method to keep the build green](0004-mikado-method-for-changes.md), [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md), [6. Keep developer and user documentation current](0006-keep-documentation-current.md)
- [4. Use the Mikado Method to keep the build green](0004-mikado-method-for-changes.md) — _Accepted_
  Large refactorings, and changes that ripple across a codebase, tempt us into long stretches where nothing compiles and nothing is committable.
  Related: [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md)
- [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md) — _Accepted_
  Standards that are not enforced erode.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md)
- [6. Keep developer and user documentation current](0006-keep-documentation-current.md) — _Accepted_
  Documentation that lags the code is worse than none — it misleads.
  Related: [1. Record architecture decisions with ADRs](0001-record-architecture-decisions.md), [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md)
- [7. Declare an explicit license and copyright](0007-license-and-copyright.md) — _Accepted_
  A repository with no license is "all rights reserved" by default — others (and future us) have no clear terms for use, and intent is ambiguous.
- [8. Maintain a security baseline](0008-security-baseline.md) — _Accepted_
  Secrets committed to a repository are effectively public and permanent — history preserves them even after deletion.

## Language

- [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md) — _Accepted_
  JVM projects need a consistent build tool, dependency management, and package organization so repositories are predictable to build and navigate, and so shared tooling (formatting, coverage, arch tests) can be applied the same way everywhere.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [11. Java language conventions](0011-java-conventions.md)
- [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md) — _Accepted_
  The universal CI-gate decision requires enforced formatting, tests, and coverage, and this project's baseline also calls for architecture tests and real-dependency integration tests.
  Related: [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md), [11. Java language conventions](0011-java-conventions.md), [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md)
- [11. Java language conventions](0011-java-conventions.md) — _Accepted_
  Java builds on the shared JVM baseline (Gradle, Spotless, JaCoCo, layered tests) and needs its language level and formatting standard pinned so Java repositories are consistent.
  Related: [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md), [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md)

## Project (marshal)

- [12. Execute the graph with a single-owner scheduler and worker pool](0012-single-owner-scheduler.md) — _Accepted_
  Live mutation plus conflicts plus priority plus concurrency is the combination that punishes shared-mutable-state designs; one owner thread turns every concurrency question into a single-threaded one.
  Related: [13. Identify nodes by object reference](0013-node-identity-by-reference.md), [14. Model dependencies and conflicts as node adjacency](0014-dependency-and-conflict-modeling.md), [15. Make representation invariants first-class](0015-invariants-first-class.md)
- [13. Identify nodes by object reference; keep scheduling metadata outside `Node`](0013-node-identity-by-reference.md) — _Accepted_
  `Node` is a pure functional interface, so nodes are lambdas with no value identity; the engine keys by object reference and keeps metadata in a separate `NodeSpec`.
  Related: [12. Single-owner scheduler](0012-single-owner-scheduler.md), [14. Dependency and conflict modeling](0014-dependency-and-conflict-modeling.md)
- [14. Model dependencies and conflicts as node adjacency; AND-join with a seam](0014-dependency-and-conflict-modeling.md) — _Accepted_
  Dependencies and conflicts are both `Map<Node,Set<Node>>` over three sources of truth; AND-join ships with a seam for `ANY`/`N_OF`; run cost is O(n log n + e + c).
  Related: [12. Single-owner scheduler](0012-single-owner-scheduler.md), [15. Make representation invariants first-class](0015-invariants-first-class.md)
- [15. Make representation invariants first-class and machine-checked](0015-invariants-first-class.md) — _Accepted_
  Denormalized indexes over the ground truth can drift; mutable state implements `Invariant`, asserted under `-ea` and shadowed by jqwik model-based tests against a reference oracle, enforced by ArchUnit.
  Related: [2. Develop with Test-Driven Development](0002-use-test-driven-development.md), [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [14. Dependency and conflict modeling](0014-dependency-and-conflict-modeling.md)
