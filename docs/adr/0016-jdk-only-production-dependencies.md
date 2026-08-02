---
status: Accepted
date: "2026-08-02"
topic: jdk-only-production-dependencies
tags: [marshal, dependencies, architecture]
supersedes: []
related: [jvm-quality-and-tests, ci-is-the-merge-gate, single-owner-scheduler]
---
# 16. Production code depends only on the JDK (zero runtime dependencies)

## Context

marshal is deliberately framework-free, and the published artifact currently pulls in
nothing but the Java standard library — a consumer adds marshal and gets marshal, with no
transitive dependencies. That property is valuable and worth protecting rather than leaving
to chance: zero runtime dependencies means no transitive version conflicts forced on
consumers, a minimal supply-chain / attack surface, and drop-in use in any environment
(including a Spring Boot app) without dragging in an external stack. The zero-dependency
state is easy to erode one convenient library at a time, so we make it a rule with a
mechanical check.

## Decision

Production code — the `main` source set / published artifact — depends **only on the Java
21+ standard library**. No external `api`, `implementation`, `compileOnly`, or `runtimeOnly`
dependencies are permitted.

- **Test-scope dependencies are unrestricted** (JUnit, jqwik, ArchUnit, AssertJ, …) — they
  never ship to consumers, so they carry none of the cost this rule targets.
- **Enforced mechanically:** a Gradle verification task (`verifyNoRuntimeDependencies`) is
  wired into `check`, so an added production dependency fails the build both locally and in
  CI ([ADR 5](0005-ci-is-the-merge-gate.md)).
- The rule is intentionally strict now. It **may be loosened via a superseding ADR** if a
  concrete need arises — the likely first candidate being compile-only nullability
  annotations, which do not ship at runtime.

## Alternatives considered

- **A small curated allowlist of production dependencies** — every dependency is a
  version-conflict and supply-chain liability for *every* consumer, and "small" allowlists
  tend to grow. Rejected in favour of zero.
- **Allow dependencies but shade/relocate them into the jar** — hides the cost rather than
  removing it: bloats the artifact, complicates stack traces, and still ships someone else's
  code. Rejected.
- **No rule, rely on code review** — the zero-dependency property erodes one convenient
  import at a time; a machine check makes it durable where review does not.

## Consequences

- Consumers get a genuinely zero-dependency library: no forced transitive versions, minimal
  attack surface, usable anywhere.
- Contributors must solve production problems with the JDK or by writing the code — never by
  reaching for a convenience library.
- The constraint can't erode silently; adding a production dependency is a build failure,
  which forces a deliberate decision (and, if intended, a superseding ADR).
- A little friction is accepted on purpose: making the rule expensive to break is the point.
