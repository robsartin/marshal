# marshal

A small, framework-free JVM library that executes a graph of nodes subject to
**predecessors**, **conflicts**, **timeouts**, and **priority** — running many
nodes at once on multiple threads, with a **mutable** execution graph that a
running node may edit.

- JVM 21+ (Java), group/package `com.robsartin.marshal`
- No Spring code; consumable from Spring Boot (wrap in a `@Bean`, supply an `Executor`)

Design: [`docs/superpowers/specs/2026-08-01-marshal-design.md`](docs/superpowers/specs/2026-08-01-marshal-design.md)
Developer architecture guide: [`docs/dev/architecture.md`](docs/dev/architecture.md)
Architecture decisions: [`docs/adr/`](docs/adr/)

## Usage

```java
import com.robsartin.marshal.Marshal;
import com.robsartin.marshal.Node;
import com.robsartin.marshal.NodeSpec;
import com.robsartin.marshal.RunReport;
import java.util.Set;

Marshal marshal = Marshal.create(); // production defaults: virtual-thread IO lane,
                                     // cores-sized CPU lane, real timeout watchdog

Node fetch = ctx -> download();
Node parse = ctx -> parse();

marshal.register(NodeSpec.of(fetch).priority(10).name("fetch").build());
marshal.register(NodeSpec.of(parse)
        .predecessors(Set.of(fetch)) // parse waits for fetch to complete
        .name("parse")
        .build());

RunReport report = marshal.run(); // blocks until every node is terminal

report.statusOf(parse); // COMPLETED | FAILED | TIMED_OUT | SKIPPED | UNREACHABLE
report.failures();      // Map<Node, Throwable> for nodes that threw
```

Register every node up front (or add more from inside a running node's
`execute(ExecutionContext ctx)` — see `ExecutionContext.addNode`/`addEdge`/
`conflict` for the live-mutation API), then call `run()` once. A node with no
predecessors starts immediately; conflicting nodes (`conflict`/`conflictGroup`)
never run at the same time; `priority` breaks ties among nodes that are ready
to run.

**Resource lifecycle:** `Marshal.create()` starts real background executors
(a virtual-thread-per-task pool, a fixed CPU pool, and a daemon timeout
scheduler) that currently have no `close()`/shutdown hook on `Marshal` itself —
tracked as [issue #2](https://github.com/robsartin/marshal/issues/2). Until
that lands, either let the process own `Marshal` for its whole lifetime, or
construct it yourself via `Marshal.create(cpuPermits, ioLane, cpuLane, timeouts)`
with executors and a `ScheduledTimeouts` you manage (and shut down) yourself.

### Using marshal from Spring Boot

The library imports nothing from Spring — there's no `@Component`/`@Bean` in
this codebase. Wrap it yourself:

```java
@Configuration
class MarshalConfig {
    @Bean
    Marshal marshal() {
        return Marshal.create();
        // or Marshal.create(cpuPermits, ioLane, cpuLane, timeouts) to supply
        // your own executors (e.g. Spring-managed thread pools) and timeout policy
    }
}
```

Since `Marshal.create()`'s executors have no managed shutdown yet (see above),
prefer the explicit-executor factory in a Spring context so the executors are
beans you already control the lifecycle of, or keep the `Marshal` bean
singleton-scoped for the application's lifetime.

Status: implemented — MVP scheduler, mutation-commit protocol, timeouts,
priority, conflicts, and the two-lane concurrent executor are all in place and
covered by the full CI gate (tests, coverage, ArchUnit, jqwik property test).
