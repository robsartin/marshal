---
status: Accepted
date: "2026-08-01"
topic: jvm-quality-and-tests
tags: [language, jvm, testing]
supersedes: []
related: [jvm-build-with-gradle, java-conventions, kotlin-conventions, ci-is-the-merge-gate]
---
# 10. Enforce JVM quality gates and layered tests

## Context

The universal CI-gate decision requires enforced formatting, tests, and coverage, and
this project's baseline also calls for architecture tests and real-dependency integration
tests. JVM projects need concrete tools so those requirements are measurable and uniform.

## Decision

Configured in Gradle and run in CI:

- **Formatting** via **Spotless**, failing the build on violations.
- **Testing** with **JUnit 5**. Fast **unit tests** are the default source set.
- **Integration tests** exercise real dependencies via **Testcontainers** (e.g. Postgres)
  and live in a separate source set / tag so they don't slow the unit loop.
- **Coverage** via **JaCoCo**, enforcing the universal thresholds — **line/instruction
  > 80%, branch > 65%** — and failing the build below them. A project may tighten these.
- **Architecture tests** via **ArchUnit** (or Kotlin-native **Konsist**) enforce module
  boundaries and forbid package cycles, failing the build on violation.

## Alternatives considered

- **JUnit 4** — still widely used, but lacks JUnit 5's extension model and parameterized-test
  ergonomics, which the layered unit/integration split relies on.
- **In-memory fakes instead of Testcontainers** (e.g. H2 for Postgres): faster to start, but
  they drift from production behavior and would undermine trust in integration tests.
- **Checkstyle/PMD instead of ArchUnit or Konsist** for boundaries: catches style issues, but
  can't express module-boundary or package-cycle rules as directly as ArchUnit/Konsist do.

## Consequences

- The universal coverage gate is concrete for the JVM, and boundary/cycle regressions are
  caught mechanically rather than in review.
- Integration tests are realistic (real services) without penalizing the fast unit loop.
- Testcontainers requires a container runtime available in CI and locally.
