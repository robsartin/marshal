# Backlog: Logging via `java.lang.System.Logger` + run/request-id correlation

**Status:** ready (to be filed as a `ready`-labeled GitHub issue once the marshal remote exists)
**Depends on:** marshal core (plan `2026-08-01-marshal-core.md`) landing first.

## Goal

Add structured logging to marshal using **`java.lang.System.Logger`** — the JDK-native
logging façade. Rationale: zero runtime dependency (keeps the no-Spring, no-third-party
constraint), and Spring Boot / SLF4J / Log4j2 consumers automatically route it through
their own `System.LoggerFinder`, so the library stays framework-free while integrating
cleanly downstream.

- Each class: `private static final System.Logger LOG = System.getLogger(<class>.class.getName());`
- Levels: `DEBUG` for per-node lifecycle, `INFO` for run start/quiescence + summary,
  `WARNING` for timeouts/rejected mutations, `ERROR` for node failures.
- No log line may include sensitive payload data — nodes are opaque user code; log the
  node's correlation token (name/id) and status, never its inputs/outputs.

## The hard part: request/run-id correlation across thread boundaries

The correlation scope is a **run** (one `Marshal.run()`). Mint a `runId` at run start.
marshal hands work across threads, and **thread-local / MDC context does NOT propagate
across those hand-offs** — so every boundary must carry the id explicitly. Touchpoints:

1. **`Marshal.run()`** — mint `runId`; log run start and final quiescence/summary.
2. **Scheduler event loop (owner thread)** — log each `Completed` / `TimedOut` with
   `runId` + node token.
3. **Dispatch → worker thread (IO/CPU lane)** — the dispatch closure must *capture*
   `runId` (and node token); the worker thread has no ambient context. **Primary touchpoint.**
4. **Timeout watchdog thread** — the armed callback fires on a separate thread; its
   closure must capture `runId` + node token to log "interrupting node X (timeout)".
5. **Mutation commit** — log applied mutations / rejected batch (cycle) with `runId` +
   origin node.
6. **Node lifecycle transitions** — dispatched / completed / failed / timed-out / skipped,
   each with `runId` + node token.

## Node correlation token — open decision

Nodes use identity-by-reference with an optional `name`. For logs we need a stable token:
- Use `NodeSpec.name` when present; otherwise assign a stable per-run ordinal/id at
  registration (e.g. `node#7`). Decide whether to add a dedicated log-id field or derive it.

## Open design question (the one Rob flagged: "touchpoints that need a request id")

Should `runId` (and node token) be **exposed to user code** so that logging *inside*
`Node.execute(ctx)` correlates with marshal's own lines?
- **Option A:** `ExecutionContext.runId()` (+ `nodeToken()`) — explicit, discoverable.
- **Option B:** bind a `ScopedValue<RunContext>` around the `node.execute(ctx)` call so
  user code (and any nested logging) inherits it without touching the signature.
- **Option C:** both — `ScopedValue` for ambient inheritance, accessor on `ctx` for
  explicit reads.
Leaning C, but this is the crux to brainstorm when the issue is picked up. ScopedValue is
JVM 21 native and fits the per-execute scope exactly.

## Acceptance sketch

- All lifecycle events emit `System.Logger` records carrying `runId` + node token.
- A test installs a custom `System.LoggerFinder` (or captures via a test handler) and
  asserts run/node correlation ids appear and are consistent across a multi-node run,
  including a node that logs from inside `execute()` on a worker thread.
