---
status: Accepted
date: "2026-08-01"
topic: java-conventions
tags: [language, java]
supersedes: []
related: [jvm-build-with-gradle, jvm-quality-and-tests]
---
# 11. Java language conventions

## Context

Java builds on the shared JVM baseline (Gradle, Spotless, JaCoCo, layered tests) and needs
its language level and formatting standard pinned so Java repositories are consistent.

## Decision

On top of the JVM baseline:

- Target a **current Java LTS** (21 unless the project states otherwise), set via the
  Gradle toolchain.
- **google-java-format** (run through Spotless) is the formatting standard.
- **ArchUnit** provides architecture tests (boundaries, no package cycles).
- Prefer modern Java: **records** for value types, `sealed` hierarchies where they model
  the domain, `Optional` over returning null at boundaries, and the `var` keyword where it
  aids readability.

## Alternatives considered

- **google-java-format's competitors (e.g. Eclipse formatter, Palantir Java Format)** — also
  viable through Spotless, but google-java-format's opinionated, zero-config style avoids
  bikeshedding over settings.
- **An older LTS (e.g. Java 17)** — still supported, but targeting the current LTS gives access
  to newer language features (records, pattern matching) this ADR relies on.
- **Lombok for boilerplate reduction** — common in older Java codebases, but records and modern
  language features cover the same ground natively, without a compile-time annotation processor.

## Consequences

- Java code is formatted uniformly and uses current language features.
- The language level is explicit and consistent across environments via the toolchain.
- Bumping the LTS target later is a deliberate, superseding decision.
- google-java-format's style is not configurable, so teams give up tuning formatting to
  local preference.
