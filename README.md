# marshal

A small, framework-free JVM library that executes a graph of nodes subject to
**predecessors**, **conflicts**, **timeouts**, and **priority** — running many
nodes at once on multiple threads, with a **mutable** execution graph that a
running node may edit.

- JVM 21+ (Java), group/package `com.robsartin.marshal`
- No Spring code; consumable from Spring Boot (wrap in a `@Bean`, supply an `Executor`)

Design: [`docs/superpowers/specs/2026-08-01-marshal-design.md`](docs/superpowers/specs/2026-08-01-marshal-design.md)

Status: **design phase** — not yet implemented.
