---
status: Accepted
date: "2026-08-01"
topic: node-identity-by-reference
tags: [marshal, api, design]
supersedes: []
related: [single-owner-scheduler, dependency-and-conflict-modeling]
---
# 13. Identify nodes by object reference; keep scheduling metadata outside `Node`

## Context

`Node` is a pure functional interface — `void execute(ExecutionContext ctx)` — so nodes
are typically lambdas. Lambdas have no meaningful value identity or `equals`, and the same
behavior written twice produces two distinct objects. The engine still needs a stable key
for each node and a home for its scheduling requirements.

## Decision

- **Identity is the object reference.** All node-keyed maps and sets use `IdentityHashMap`
  / identity-backed sets; the engine never relies on `Node.equals`. One `Node` instance is
  one graph node. Registering the same instance twice is a no-op; to run the same behavior
  at two points, create two lambdas.
- **Metadata lives in `NodeSpec`, not on `Node`.** Priority, timeout, `ExecutionKind`,
  predecessors, conflicts, and an optional name are carried by a separate registration
  record. `Node` stays pure behavior.

## Alternatives considered

- **A dedicated `NodeId` handle type** — a stable, printable, serializable id, but an extra
  type threaded through the whole API; rejected for v1 in favor of the simpler reference
  model (a `NodeId` can be added later if cross-process identity is ever needed).
- **Value-equality on `Node`** — would silently merge two distinct nodes that happen to be
  `equal`; `IdentityHashMap` deliberately avoids this.
- **Metadata on the node type** via marker interfaces / sub-interfaces (`CpuNode`) — forces
  intersection-type casts on lambdas and puts scheduling concerns onto behavior.

## Consequences

- The API is minimal: `register`, `conflict`, `dependency` all take `Node` directly.
- A node has no human-friendly identifier in logs unless given a `name`; the optional name
  field exists for exactly that.
- Identity keying is a deliberate, documented rule (`IdentityHashMap` everywhere).
