# marshal Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `marshal`, a framework-free JVM library that executes a mutable graph of nodes subject to predecessors, conflicts, timeouts, and priority, running many nodes at once on multiple threads.

**Architecture:** A single "owner" scheduler thread holds all mutable graph state and runs an event loop; worker threads only run each node's synchronous `execute` and post results back. Graph mutations made by a running node are buffered on its `ExecutionContext` and committed atomically at completion through invariant-preserving mutators. All shared state lives behind that single thread, so there are no locks.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), JUnit 5, jqwik (property/model-based tests), ArchUnit (architecture tests), JaCoCo (coverage), Spotless + Palantir Java Format.

## Global Constraints

- Java **21+**; group and base package **`com.robsartin.marshal`**.
- **No Spring dependency** anywhere in `main`; the library must be consumable from Spring Boot by wrapping it in a `@Bean`.
- Gradle runs on **JDK 21**. This host has it via Homebrew `openjdk@21` at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (the default JDK is 25, which the pinned Gradle 8.10.2 cannot launch on). Run **every** Gradle command with that JDK: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` first, then `./gradlew ...`. The build also pins a Gradle **toolchain** of 21 so compilation is deterministic regardless of launcher JVM.
- **Tests run with assertions enabled (`-ea`)** — the invariant discipline depends on it. The Gradle `test` task sets `jvmArgs("-ea")`.
- **Node identity is object reference.** All node-keyed maps/sets use `IdentityHashMap` / `Collections.newSetFromMap(new IdentityHashMap<>())`. Never rely on `Node.equals`.
- **TDD, always:** red → green → refactor → commit. The failing test runs and fails *before* the implementation exists.
- **Full CI gate before any push:** `spotlessCheck`, `test` (with `-ea`), `jacocoTestCoverageVerification` (line > 80%, branch > 65%), and the ArchUnit tests all pass.
- **Workflow:** create a GitHub issue for the increment, branch off `main`, commit per task, open a PR to `main`, squash-merge. Never commit directly to `main` after the initial scaffold.
- **YAGNI:** build only what the spec's v1 scope requires; the spec's Section 11 deferrals stay deferred.

**Spec:** [`docs/superpowers/specs/2026-08-01-marshal-design.md`](../specs/2026-08-01-marshal-design.md)

---

## File Structure

Main (`src/main/java/com/robsartin/marshal/`):

- `Node.java` — `@FunctionalInterface void execute(ExecutionContext)`.
- `ExecutionKind.java` — enum `IO`, `CPU`.
- `Status.java` — enum `WAITING, READY, RUNNING, COMPLETED, FAILED, TIMED_OUT, SKIPPED, UNREACHABLE`.
- `NodeSpec.java` — record of scheduling metadata + a `Builder`.
- `ExecutionContext.java` — read-snapshot + buffered-mutation API given to a running node.
- `Invariant.java` — `void invariant()`.
- `Outcome.java` — sealed result of one `execute` (`Success`, `Failure(Throwable)`).
- `Mutation.java` — sealed buffered graph edit (`AddNode`, `RemoveNode`, `AddEdge`, `RemoveEdge`, `AddConflict`, `AddConflictGroup`).
- `Event.java` — sealed scheduler event (`Completed`, `TimedOut`, `Stop`).
- `GraphState.java` — the mutable owner-thread state: indexes, mutators, status transitions, `invariant()`. Implements `Invariant`.
- `Selection.java` — pure "what may start now" function over a `GraphState` snapshot + free permits.
- `BufferingExecutionContext.java` — `ExecutionContext` impl that buffers mutations and drains them.
- `Timeouts.java` — interface `arm(Node, Duration, Runnable)` / `cancel(Node)`; `ScheduledTimeouts` real impl.
- `RunReport.java` — per-node terminal `Status` (+ failure causes).
- `Marshal.java` — public entry: registration, the scheduler thread + event loop, `run()`.

Test (`src/test/java/com/robsartin/marshal/`):

- Mirror unit tests per class.
- `support/ReferenceGraphModel.java` — naive oracle recomputing indexes from ground truth.
- `support/InlineExecutor.java` — runs tasks on the calling thread.
- `support/ManualTimeouts.java` — `Timeouts` driven by a fake `Clock` for deterministic timeout tests.
- `GraphStateModelPropertyTest.java` — jqwik stateful property test (engine vs oracle).
- `ArchitectureTest.java` — ArchUnit rules (incl. the `Invariant` rule, no-Spring rule).

---

## Task 0: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `src/main/java/com/robsartin/marshal/package-info.java`
- Test: `src/test/java/com/robsartin/marshal/SmokeTest.java`

**Interfaces:**
- Produces: a green `./gradlew test` with JUnit 5, jqwik, ArchUnit, JaCoCo, Spotless wired in.

- [ ] **Step 1: Generate the Gradle wrapper (pin 8.10.2)**

Run (with a JDK 21 launcher): `gradle wrapper --gradle-version 8.10.2` — or hand-write `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2: Version catalog `gradle/libs.versions.toml`**

```toml
[versions]
junit = "5.11.3"
jqwik = "1.9.1"
archunit = "1.3.0"
assertj = "3.26.3"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
jqwik = { module = "net.jqwik:jqwik", version.ref = "jqwik" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
```

- [ ] **Step 3: `settings.gradle.kts`**

```kotlin
rootProject.name = "marshal"
```

- [ ] **Step 4: `build.gradle.kts`**

```kotlin
plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.robsartin"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    jvmArgs("-ea")                     // assertions on: the invariant discipline needs this
    finalizedBy(tasks.jacocoTestReport)
}

spotless {
    java { palantirJavaFormat() }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.65".toBigDecimal() }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
```

- [ ] **Step 5: `package-info.java`**

```java
/** marshal — a framework-free JVM graph executor. */
package com.robsartin.marshal;
```

- [ ] **Step 6: Write the smoke test (must fail first — file/class absent)**

`SmokeTest.java`:

```java
package com.robsartin.marshal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void assertionsAreEnabled() {
        boolean enabled = false;
        assert (enabled = true);            // flips only if -ea is on
        assertTrue(enabled, "run tests with -ea");
    }
}
```

- [ ] **Step 7: Run and verify green**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test`
Expected: PASS (confirms toolchain, JUnit, and `-ea` are all wired).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "build: scaffold gradle project with junit5, jqwik, archunit, jacoco, spotless"
```

---

## Task 1: Core value types

**Files:**
- Create: `Node.java`, `ExecutionKind.java`, `Status.java`, `Invariant.java`, `NodeSpec.java`
- Test: `NodeSpecTest.java`

**Interfaces:**
- Produces:
  - `interface Node { void execute(ExecutionContext ctx); }`
  - `enum ExecutionKind { IO, CPU }`
  - `enum Status { WAITING, READY, RUNNING, COMPLETED, FAILED, TIMED_OUT, SKIPPED, UNREACHABLE }`
  - `interface Invariant { void invariant(); }`
  - `record NodeSpec(Node behavior, int priority, Duration timeout, ExecutionKind kind, Set<Node> predecessors, Set<Node> conflicts, String name)` with a `NodeSpec.Builder` and `NodeSpec.of(Node)`.

Note: `ExecutionContext` is referenced by `Node` but is fully defined in Task 5. For this task, create a **minimal placeholder-free** `ExecutionContext` marker now and expand it in Task 5:

```java
package com.robsartin.marshal;
/** Handle a running node uses to read state and buffer graph mutations. Expanded in Task 5. */
public interface ExecutionContext {
    boolean isCompleted(Node node);
}
```

- [ ] **Step 1: Write the failing test**

`NodeSpecTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeSpecTest {
    @Test
    void builderDefaultsKindToIoAndNoTimeout() {
        Node n = ctx -> {};
        NodeSpec spec = NodeSpec.of(n).priority(5).name("load").build();
        assertThat(spec.kind()).isEqualTo(ExecutionKind.IO);
        assertThat(spec.timeout()).isNull();
        assertThat(spec.priority()).isEqualTo(5);
        assertThat(spec.behavior()).isSameAs(n);
        assertThat(spec.predecessors()).isEmpty();
    }

    @Test
    void canonicalConstructorRejectsNullBehaviorAndCopiesSets() {
        assertThatThrownBy(() -> new NodeSpec(null, 0, null, ExecutionKind.IO, Set.of(), Set.of(), null))
            .isInstanceOf(NullPointerException.class);
        Set<Node> preds = new java.util.HashSet<>();
        Node a = ctx -> {};
        preds.add(a);
        NodeSpec spec = new NodeSpec(ctx -> {}, 0, Duration.ofSeconds(1), ExecutionKind.CPU, preds, Set.of(), "x");
        preds.clear();                                   // must not affect the spec (defensive copy)
        assertThat(spec.predecessors()).containsExactly(a);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests NodeSpecTest`
Expected: FAIL (compilation error — `NodeSpec` / `Node` absent).

- [ ] **Step 3: Write the types**

`Node.java`:

```java
package com.robsartin.marshal;

@FunctionalInterface
public interface Node {
    void execute(ExecutionContext ctx);
}
```

`ExecutionKind.java`:

```java
package com.robsartin.marshal;

public enum ExecutionKind { IO, CPU }
```

`Status.java`:

```java
package com.robsartin.marshal;

public enum Status {
    WAITING, READY, RUNNING, COMPLETED, FAILED, TIMED_OUT, SKIPPED, UNREACHABLE
}
```

`Invariant.java`:

```java
package com.robsartin.marshal;

public interface Invariant {
    /** @throws IllegalStateException if the representation invariant is violated. */
    void invariant();
}
```

`NodeSpec.java`:

```java
package com.robsartin.marshal;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record NodeSpec(
        Node behavior,
        int priority,
        Duration timeout,          // null == no timeout
        ExecutionKind kind,
        Set<Node> predecessors,
        Set<Node> conflicts,
        String name) {

    public NodeSpec {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(kind, "kind");
        predecessors = Set.copyOf(predecessors == null ? Set.of() : predecessors);
        conflicts = Set.copyOf(conflicts == null ? Set.of() : conflicts);
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive or null: " + timeout);
        }
    }

    public static Builder of(Node behavior) {
        return new Builder(behavior);
    }

    public static final class Builder {
        private final Node behavior;
        private int priority = 0;
        private Duration timeout = null;
        private ExecutionKind kind = ExecutionKind.IO;
        private Set<Node> predecessors = Set.of();
        private Set<Node> conflicts = Set.of();
        private String name = null;

        private Builder(Node behavior) { this.behavior = behavior; }

        public Builder priority(int p) { this.priority = p; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder kind(ExecutionKind k) { this.kind = k; return this; }
        public Builder predecessors(Set<Node> p) { this.predecessors = p; return this; }
        public Builder conflicts(Set<Node> c) { this.conflicts = c; return this; }
        public Builder name(String n) { this.name = n; return this; }

        public NodeSpec build() {
            return new NodeSpec(behavior, priority, timeout, kind, predecessors, conflicts, name);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests NodeSpecTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: core value types (Node, NodeSpec, ExecutionKind, Status, Invariant)"
```

---

## Task 2: GraphState — nodes, edges, readiness, and invariant()

**Files:**
- Create: `GraphState.java`
- Test: `GraphStateEdgesTest.java`

**Interfaces:**
- Consumes: `Node`, `NodeSpec`, `Status`, `Invariant` (Task 1).
- Produces on `GraphState` (all node-keyed structures use identity):
  - `void addNode(NodeSpec spec)` — key = `spec.behavior()`; status `WAITING`; also applies declared `predecessors`/`conflicts` as edges. Re-adding an existing node is a no-op.
  - `void addEdge(Node predecessor, Node successor)` — updates `successors`, `predecessors`, and `remainingPreds[successor]` (only counts predecessors not yet `COMPLETED`).
  - `void removeEdge(Node predecessor, Node successor)`.
  - `void removeNode(Node n)` — purges `n` from every other node's pred/succ/conflict set.
  - `boolean contains(Node n)`, `Status status(Node n)`, `int remainingPreds(Node n)`.
  - `Set<Node> nodes()`, `Set<Node> successors(Node n)`, `Set<Node> predecessors(Node n)`.
  - `boolean wouldIntroduceCycle(Node predecessor, Node successor)` — reachability check.
  - `void invariant()` (implements `Invariant`).
- Conflicts and status transitions come in Task 2b (next task); this task keeps `conflicts` maps present but only exercised by `invariant()`'s symmetry check via a stub `addConflict` added in 2b. To keep `invariant()` complete now, include the conflict maps initialized empty.

- [ ] **Step 1: Write the failing test**

`GraphStateEdgesTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphStateEdgesTest {
    private static NodeSpec spec(Node n) { return NodeSpec.of(n).build(); }

    @Test
    void addEdgeMaintainsBothDirectionsAndRemainingCount() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);

        assertThat(g.successors(a)).containsExactly(b);
        assertThat(g.predecessors(b)).containsExactly(a);
        assertThat(g.remainingPreds(b)).isEqualTo(1);
        assertThat(g.remainingPreds(a)).isEqualTo(0);
        g.invariant();                    // must not throw
    }

    @Test
    void removeNodePurgesDanglingReferences() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a));
        g.addNode(spec(b));
        g.addEdge(a, b);
        g.removeNode(a);

        assertThat(g.contains(a)).isFalse();
        assertThat(g.predecessors(b)).isEmpty();
        assertThat(g.remainingPreds(b)).isEqualTo(0);
        g.invariant();
    }

    @Test
    void wouldIntroduceCycleDetectsBackEdge() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b)); g.addNode(spec(c));
        g.addEdge(a, b);
        g.addEdge(b, c);
        assertThat(g.wouldIntroduceCycle(c, a)).isTrue();   // c->a closes a->b->c->a
        assertThat(g.wouldIntroduceCycle(a, c)).isFalse();  // a->c is a valid forward edge
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests GraphStateEdgesTest`
Expected: FAIL (compilation — `GraphState` absent).

- [ ] **Step 3: Implement `GraphState` (edges + readiness + invariant)**

```java
package com.robsartin.marshal;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class GraphState implements Invariant {

    private final Map<Node, NodeSpec> specs = new IdentityHashMap<>();
    private final Map<Node, Status> status = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> successors = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> predecessors = new IdentityHashMap<>();
    private final Map<Node, Set<Node>> conflicts = new IdentityHashMap<>();
    private final Map<Node, Integer> remainingPreds = new IdentityHashMap<>();

    private static Set<Node> idSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // ---- mutation -------------------------------------------------------

    public void addNode(NodeSpec spec) {
        Node n = spec.behavior();
        if (specs.containsKey(n)) return;                 // idempotent
        specs.put(n, spec);
        status.put(n, Status.WAITING);
        successors.put(n, idSet());
        predecessors.put(n, idSet());
        conflicts.put(n, idSet());
        remainingPreds.put(n, 0);
        for (Node p : spec.predecessors()) addEdge(p, n);
        for (Node c : spec.conflicts()) addConflict(n, c);
        assert holds();
    }

    public void addEdge(Node predecessor, Node successor) {
        require(predecessor);
        require(successor);
        if (successors.get(predecessor).add(successor)) {
            predecessors.get(successor).add(predecessor);
            if (status.get(predecessor) != Status.COMPLETED) {
                remainingPreds.merge(successor, 1, Integer::sum);
            }
        }
        assert holds();
    }

    public void removeEdge(Node predecessor, Node successor) {
        if (successors.getOrDefault(predecessor, Set.of()).remove(successor)) {
            predecessors.get(successor).remove(predecessor);
            if (status.get(predecessor) != Status.COMPLETED) {
                remainingPreds.merge(successor, -1, Integer::sum);
            }
        }
        assert holds();
    }

    public void removeNode(Node n) {
        if (!specs.containsKey(n)) return;
        for (Node s : Set.copyOf(successors.get(n))) removeEdge(n, s);
        for (Node p : Set.copyOf(predecessors.get(n))) removeEdge(p, n);
        for (Node c : Set.copyOf(conflicts.get(n))) removeConflict(n, c);
        specs.remove(n);
        status.remove(n);
        successors.remove(n);
        predecessors.remove(n);
        conflicts.remove(n);
        remainingPreds.remove(n);
        assert holds();
    }

    // addConflict/removeConflict fully exercised in Task 2b; defined here so invariant() is complete.
    public void addConflict(Node a, Node b) {
        require(a); require(b);
        if (a == b) throw new IllegalArgumentException("a node cannot conflict with itself");
        conflicts.get(a).add(b);
        conflicts.get(b).add(a);
        assert holds();
    }

    public void removeConflict(Node a, Node b) {
        conflicts.getOrDefault(a, Set.of()).remove(b);
        conflicts.getOrDefault(b, Set.of()).remove(a);
        assert holds();
    }

    // ---- queries --------------------------------------------------------

    public boolean contains(Node n) { return specs.containsKey(n); }
    public Set<Node> nodes() { return Collections.unmodifiableSet(specs.keySet()); }
    public Status status(Node n) { return status.get(n); }
    public NodeSpec spec(Node n) { return specs.get(n); }
    public int remainingPreds(Node n) { return remainingPreds.get(n); }
    public Set<Node> successors(Node n) { return Collections.unmodifiableSet(successors.get(n)); }
    public Set<Node> predecessors(Node n) { return Collections.unmodifiableSet(predecessors.get(n)); }
    public Set<Node> conflicts(Node n) { return Collections.unmodifiableSet(conflicts.get(n)); }

    public boolean wouldIntroduceCycle(Node predecessor, Node successor) {
        // adding predecessor->successor creates a cycle iff predecessor is already reachable from successor
        if (predecessor == successor) return true;
        var stack = new ArrayDeque<Node>();
        var seen = idSet();
        stack.push(successor);
        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            if (cur == predecessor) return true;
            if (!seen.add(cur)) continue;
            for (Node s : successors.getOrDefault(cur, Set.of())) stack.push(s);
        }
        return false;
    }

    // ---- invariant ------------------------------------------------------

    private boolean holds() { invariant(); return true; }

    @Override
    public void invariant() {
        for (Node a : specs.keySet()) {
            if (conflicts.get(a).contains(a)) throw new IllegalStateException("conflict irreflexive violated: " + a);
            for (Node b : successors.get(a)) {
                if (!specs.containsKey(b)) throw new IllegalStateException("dangling successor " + b);
                if (!predecessors.get(b).contains(a)) throw new IllegalStateException("transpose violated: " + a + "->" + b);
            }
            for (Node b : conflicts.get(a)) {
                if (!specs.containsKey(b)) throw new IllegalStateException("dangling conflict " + b);
                if (!conflicts.get(b).contains(a)) throw new IllegalStateException("conflict symmetry violated: " + a + "," + b);
            }
            long unmet = predecessors.get(a).stream().filter(p -> status.get(p) != Status.COMPLETED).count();
            if (remainingPreds.get(a) != unmet) {
                throw new IllegalStateException("remainingPreds stale for " + a + ": " + remainingPreds.get(a) + " != " + unmet);
            }
        }
    }

    private void require(Node n) {
        if (!specs.containsKey(n)) throw new IllegalArgumentException("unknown node: " + n);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests GraphStateEdgesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: GraphState edges, readiness counter, cycle check, invariant()"
```

---

## Task 2b: GraphState — status transitions and skip propagation

**Files:**
- Modify: `GraphState.java`
- Test: `GraphStateStatusTest.java`

**Interfaces:**
- Produces on `GraphState`:
  - `void markReady(Node n)` — `WAITING` → `READY` (guard: `remainingPreds == 0`).
  - `void markRunning(Node n)` — `READY` → `RUNNING`.
  - `void markCompleted(Node n)` — `RUNNING` → `COMPLETED`; decrement each successor's `remainingPreds`.
  - `void fail(Node n, Status cause)` — `cause` ∈ {`FAILED`, `TIMED_OUT`}; set status, then transitively mark not-yet-terminal successors `SKIPPED`.
  - `Set<Node> readyPromotable()` — nodes currently `WAITING` with `remainingPreds == 0`.

- [ ] **Step 1: Write the failing test**

`GraphStateStatusTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphStateStatusTest {
    private static NodeSpec spec(Node n) { return NodeSpec.of(n).build(); }

    @Test
    void completingPredecessorPromotesSuccessor() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b));
        g.addEdge(a, b);

        g.markReady(a); g.markRunning(a); g.markCompleted(a);

        assertThat(g.remainingPreds(b)).isEqualTo(0);
        assertThat(g.readyPromotable()).containsExactly(b);
        g.invariant();
    }

    @Test
    void failureSkipsTransitiveDependentsButNotSiblings() {
        GraphState g = new GraphState();
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {}, indep = ctx -> {};
        g.addNode(spec(a)); g.addNode(spec(b)); g.addNode(spec(c)); g.addNode(spec(indep));
        g.addEdge(a, b);
        g.addEdge(b, c);

        g.markReady(a); g.markRunning(a);
        g.fail(a, Status.FAILED);

        assertThat(g.status(b)).isEqualTo(Status.SKIPPED);
        assertThat(g.status(c)).isEqualTo(Status.SKIPPED);
        assertThat(g.status(indep)).isEqualTo(Status.WAITING);
        g.invariant();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests GraphStateStatusTest`
Expected: FAIL (methods absent).

- [ ] **Step 3: Add the transitions to `GraphState`**

```java
    public void markReady(Node n) {
        expect(n, Status.WAITING);
        if (remainingPreds.get(n) != 0) throw new IllegalStateException("preds unmet: " + n);
        status.put(n, Status.READY);
        assert holds();
    }

    public void markRunning(Node n) {
        expect(n, Status.READY);
        status.put(n, Status.RUNNING);
        assert holds();
    }

    public void markCompleted(Node n) {
        expect(n, Status.RUNNING);
        status.put(n, Status.COMPLETED);
        for (Node s : successors.get(n)) remainingPreds.merge(s, -1, Integer::sum);
        assert holds();
    }

    public void fail(Node n, Status cause) {
        if (cause != Status.FAILED && cause != Status.TIMED_OUT) {
            throw new IllegalArgumentException("cause must be FAILED or TIMED_OUT: " + cause);
        }
        status.put(n, cause);
        for (Node s : Set.copyOf(successors.get(n))) skip(s);
        assert holds();
    }

    private void skip(Node n) {
        Status cur = status.get(n);
        if (cur == Status.COMPLETED || cur == Status.FAILED || cur == Status.TIMED_OUT
                || cur == Status.SKIPPED || cur == Status.RUNNING) {
            return;                       // already terminal or in-flight; do not disturb
        }
        status.put(n, Status.SKIPPED);
        for (Node s : Set.copyOf(successors.get(n))) skip(s);
    }

    public Set<Node> readyPromotable() {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : specs.keySet()) {
            if (status.get(n) == Status.WAITING && remainingPreds.get(n) == 0) out.add(n);
        }
        return out;
    }

    private void expect(Node n, Status expected) {
        require(n);
        if (status.get(n) != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status.get(n) + " for " + n);
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests GraphStateStatusTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: GraphState status transitions and transitive skip propagation"
```

---

## Task 3: Reference oracle + jqwik model-based property test

**Files:**
- Create: `src/test/java/com/robsartin/marshal/support/ReferenceGraphModel.java`
- Test: `GraphStateModelPropertyTest.java`

**Interfaces:**
- Consumes: `GraphState` (Tasks 2, 2b).
- Produces: a jqwik `@Property` that drives random operation sequences through both `GraphState` and `ReferenceGraphModel` and asserts (a) `GraphState.invariant()` never throws and (b) the engine's derived views equal the oracle's recomputed views after every step.

The oracle stores only ground truth — `Set<Node> nodes`, `List<Edge> deps`, `Set<UnorderedPair> conflicts`, `Map<Node,Status> status` — and recomputes `successors/predecessors/remainingPreds` from scratch on demand.

- [ ] **Step 1: Write the oracle**

`support/ReferenceGraphModel.java`:

```java
package com.robsartin.marshal.support;

import com.robsartin.marshal.Node;
import com.robsartin.marshal.Status;
import java.util.*;

/** Naive, obviously-correct shadow of GraphState: stores ground truth, recomputes indexes. */
public final class ReferenceGraphModel {
    public final Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    public final Map<Node, Status> status = new IdentityHashMap<>();
    private final List<Node[]> deps = new ArrayList<>();          // {pred, succ}

    public void addNode(Node n) { if (nodes.add(n)) status.put(n, Status.WAITING); }

    public void addEdge(Node p, Node s) {
        if (nodes.contains(p) && nodes.contains(s) && !hasEdge(p, s)) deps.add(new Node[]{p, s});
    }

    public void removeEdge(Node p, Node s) {
        deps.removeIf(e -> e[0] == p && e[1] == s);
    }

    public void removeNode(Node n) {
        nodes.remove(n); status.remove(n);
        deps.removeIf(e -> e[0] == n || e[1] == n);
    }

    public void setStatus(Node n, Status st) { if (nodes.contains(n)) status.put(n, st); }

    public boolean hasEdge(Node p, Node s) {
        for (Node[] e : deps) if (e[0] == p && e[1] == s) return true;
        return false;
    }

    public Set<Node> successors(Node n) {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node[] e : deps) if (e[0] == n) out.add(e[1]);
        return out;
    }

    public Set<Node> predecessors(Node n) {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node[] e : deps) if (e[1] == n) out.add(e[0]);
        return out;
    }

    public int remainingPreds(Node n) {
        int c = 0;
        for (Node[] e : deps) if (e[1] == n && status.get(e[0]) != Status.COMPLETED) c++;
        return c;
    }
}
```

- [ ] **Step 2: Write the failing property test**

`GraphStateModelPropertyTest.java`:

```java
package com.robsartin.marshal;

import com.robsartin.marshal.support.ReferenceGraphModel;
import java.util.*;
import net.jqwik.api.*;
import net.jqwik.api.stateful.*;
import org.assertj.core.api.Assertions;

class GraphStateModelPropertyTest {

    @Property(tries = 500)
    void engineMatchesOracle(@ForAll("sequences") ActionSequence<Pair> seq) {
        seq.run(new Pair(new GraphState(), new ReferenceGraphModel(), new ArrayList<>()));
    }

    @Provide
    Arbitrary<ActionSequence<Pair>> sequences() {
        return Arbitraries.sequences(Arbitraries.oneOf(addNode(), addEdge(), complete()));
    }

    // --- shared mutable holder driven through the sequence ---
    record Pair(GraphState g, ReferenceGraphModel ref, List<Node> created) {}

    private Arbitrary<Action<Pair>> addNode() {
        return Arbitraries.just(new Action<>() {
            public Pair run(Pair p) {
                Node n = ctx -> {};
                p.created().add(n);
                p.g().addNode(NodeSpec.of(n).build());
                p.ref().addNode(n);
                check(p);
                return p;
            }
        });
    }

    private Arbitrary<Action<Pair>> addEdge() {
        return Arbitraries.integers().between(0, 20).tuple2().map(t -> new Action<>() {
            public boolean precondition(Pair p) { return p.created().size() >= 2; }
            public Pair run(Pair p) {
                var c = p.created();
                Node a = c.get(t.get1() % c.size());
                Node b = c.get(t.get2() % c.size());
                if (a != b && !p.g().wouldIntroduceCycle(a, b)) {
                    p.g().addEdge(a, b);
                    p.ref().addEdge(a, b);
                }
                check(p);
                return p;
            }
        });
    }

    private Arbitrary<Action<Pair>> complete() {
        return Arbitraries.integers().between(0, 20).map(i -> new Action<>() {
            public boolean precondition(Pair p) { return !p.created().isEmpty(); }
            public Pair run(Pair p) {
                var c = p.created();
                Node n = c.get(i % c.size());
                if (p.g().status(n) == Status.WAITING && p.g().remainingPreds(n) == 0) {
                    p.g().markReady(n); p.g().markRunning(n); p.g().markCompleted(n);
                    p.ref().setStatus(n, Status.COMPLETED);
                }
                check(p);
                return p;
            }
        });
    }

    private static void check(Pair p) {
        p.g().invariant();                                  // never throws
        for (Node n : p.created()) {
            if (!p.g().contains(n)) continue;
            Assertions.assertThat(p.g().successors(n)).isEqualTo(p.ref().successors(n));
            Assertions.assertThat(p.g().predecessors(n)).isEqualTo(p.ref().predecessors(n));
            Assertions.assertThat(p.g().remainingPreds(n)).isEqualTo(p.ref().remainingPreds(n));
        }
    }
}
```

- [ ] **Step 3: Run to verify it fails, then passes**

Run: `./gradlew test --tests GraphStateModelPropertyTest`
Expected: FAIL first if any mutator is inconsistent; once green, the denormalized indexes are shadow-verified. If it fails, fix the mutator in `GraphState` (not the oracle) until green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: jqwik model-based property test shadowing GraphState against an oracle"
```

---

## Task 4: Pure selection function

**Files:**
- Create: `Selection.java`
- Test: `SelectionTest.java`

**Interfaces:**
- Consumes: `GraphState`, `NodeSpec`, `ExecutionKind`, `Status`.
- Produces:
  - `record Dispatch(Node node, ExecutionKind lane) {}`
  - `static List<Dispatch> select(GraphState g, Set<Node> ready, Set<Node> running, int freeCpuPermits, int freeIoPermits)` — pure. Repeatedly pick the highest-priority ready node whose `conflicts(node)` does not intersect `running` (nor any already-selected node) and whose lane has a free permit; decrement that lane's local permit count; stop when none qualifies. Ties broken by insertion order via a stable comparator on priority only.

- [ ] **Step 1: Write the failing test**

`SelectionTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;

class SelectionTest {
    private static Set<Node> idSet(Node... ns) {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(Arrays.asList(ns));
        return s;
    }

    @Test
    void picksHighestPriorityFirstAndRespectsConflicts() {
        GraphState g = new GraphState();
        Node hi = ctx -> {}, lo = ctx -> {}, foe = ctx -> {};
        g.addNode(NodeSpec.of(hi).priority(10).build());
        g.addNode(NodeSpec.of(lo).priority(1).build());
        g.addNode(NodeSpec.of(foe).priority(5).build());
        g.addConflict(hi, foe);                              // hi and foe cannot co-run

        List<Selection.Dispatch> out =
            Selection.select(g, idSet(hi, lo, foe), idSet(), 8, 8);

        // hi wins on priority; foe is then blocked by the just-selected hi; lo also dispatched
        assertThat(out).extracting(Selection.Dispatch::node).containsExactly(hi, lo);
    }

    @Test
    void cpuPermitsCapCpuLaneButNotIoLane() {
        GraphState g = new GraphState();
        Node cpu1 = ctx -> {}, cpu2 = ctx -> {}, io1 = ctx -> {};
        g.addNode(NodeSpec.of(cpu1).priority(9).kind(ExecutionKind.CPU).build());
        g.addNode(NodeSpec.of(cpu2).priority(8).kind(ExecutionKind.CPU).build());
        g.addNode(NodeSpec.of(io1).priority(1).kind(ExecutionKind.IO).build());

        List<Selection.Dispatch> out =
            Selection.select(g, idSet(cpu1, cpu2, io1), idSet(), 1, 8);   // only 1 CPU permit

        assertThat(out).extracting(Selection.Dispatch::node).containsExactly(cpu1, io1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SelectionTest`
Expected: FAIL (`Selection` absent).

- [ ] **Step 3: Implement `Selection`**

```java
package com.robsartin.marshal;

import java.util.*;

public final class Selection {
    private Selection() {}

    public record Dispatch(Node node, ExecutionKind lane) {}

    public static List<Dispatch> select(
            GraphState g, Set<Node> ready, Set<Node> running,
            int freeCpuPermits, int freeIoPermits) {

        List<Node> candidates = new ArrayList<>(ready);
        candidates.sort(Comparator.comparingInt((Node n) -> g.spec(n).priority()).reversed());

        Set<Node> committed = Collections.newSetFromMap(new IdentityHashMap<>());
        committed.addAll(running);
        List<Dispatch> out = new ArrayList<>();
        int cpu = freeCpuPermits, io = freeIoPermits;

        for (Node n : candidates) {
            if (intersects(g.conflicts(n), committed)) continue;
            ExecutionKind lane = g.spec(n).kind();
            if (lane == ExecutionKind.CPU) {
                if (cpu <= 0) continue;
                cpu--;
            } else {
                if (io <= 0) continue;
                io--;
            }
            committed.add(n);
            out.add(new Dispatch(n, lane));
        }
        return out;
    }

    private static boolean intersects(Set<Node> a, Set<Node> b) {
        Set<Node> small = a.size() <= b.size() ? a : b;
        Set<Node> large = small == a ? b : a;
        for (Node n : small) if (large.contains(n)) return true;
        return false;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests SelectionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: pure Selection function (priority + conflicts + lane permits)"
```

---

## Task 5: ExecutionContext, Mutation, Outcome, Event

**Files:**
- Modify: `ExecutionContext.java`
- Create: `Mutation.java`, `Outcome.java`, `Event.java`, `BufferingExecutionContext.java`
- Test: `BufferingExecutionContextTest.java`

**Interfaces:**
- Produces:
  - Expanded `ExecutionContext`: `boolean isCompleted(Node)`, `void addNode(NodeSpec)`, `void removeNode(Node)`, `void addEdge(Node,Node)`, `void removeEdge(Node,Node)`, `void conflict(Node,Node)`, `void conflictGroup(Set<Node>)`.
  - `sealed interface Mutation` permitting records `AddNode(NodeSpec spec)`, `RemoveNode(Node node)`, `AddEdge(Node pred, Node succ)`, `RemoveEdge(Node pred, Node succ)`, `AddConflict(Node a, Node b)`, `AddConflictGroup(Set<Node> nodes)`.
  - `sealed interface Outcome` permitting `Success` (singleton) and `Failure(Throwable cause)`.
  - `sealed interface Event` permitting `Completed(Node node, Outcome outcome, List<Mutation> mutations)`, `TimedOut(Node node)`, `Stop`.
  - `BufferingExecutionContext implements ExecutionContext` with `List<Mutation> drain()`, constructed with a `Predicate<Node> completedView`.

- [ ] **Step 1: Write the failing test**

`BufferingExecutionContextTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BufferingExecutionContextTest {
    @Test
    void buffersMutationsInOrderAndDrainsOnce() {
        Node added = ctx -> {};
        BufferingExecutionContext ctx = new BufferingExecutionContext(n -> false);
        ctx.addNode(NodeSpec.of(added).build());
        ctx.addEdge(added, added);                      // order preserved even if nonsensical here

        List<Mutation> drained = ctx.drain();
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0)).isInstanceOf(Mutation.AddNode.class);
        assertThat(drained.get(1)).isInstanceOf(Mutation.AddEdge.class);
        assertThat(ctx.drain()).isEmpty();              // idempotent drain
    }

    @Test
    void conflictGroupExpandsToPairwiseAddConflict() {
        Node a = ctx -> {}, b = ctx -> {}, c = ctx -> {};
        BufferingExecutionContext ctx = new BufferingExecutionContext(n -> false);
        ctx.conflictGroup(Set.of(a, b, c));
        long pairs = ctx.drain().stream().filter(m -> m instanceof Mutation.AddConflict).count();
        assertThat(pairs).isEqualTo(3);                 // {a,b},{a,c},{b,c}
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests BufferingExecutionContextTest`
Expected: FAIL (types absent).

- [ ] **Step 3: Implement the types**

`ExecutionContext.java` (replace the Task 1 placeholder):

```java
package com.robsartin.marshal;

import java.util.Set;

public interface ExecutionContext {
    boolean isCompleted(Node node);
    void addNode(NodeSpec spec);
    void removeNode(Node node);
    void addEdge(Node predecessor, Node successor);
    void removeEdge(Node predecessor, Node successor);
    void conflict(Node a, Node b);
    void conflictGroup(Set<Node> nodes);
}
```

`Mutation.java`:

```java
package com.robsartin.marshal;

import java.util.Set;

public sealed interface Mutation {
    record AddNode(NodeSpec spec) implements Mutation {}
    record RemoveNode(Node node) implements Mutation {}
    record AddEdge(Node predecessor, Node successor) implements Mutation {}
    record RemoveEdge(Node predecessor, Node successor) implements Mutation {}
    record AddConflict(Node a, Node b) implements Mutation {}
    record AddConflictGroup(Set<Node> nodes) implements Mutation {}
}
```

`Outcome.java`:

```java
package com.robsartin.marshal;

public sealed interface Outcome {
    record Success() implements Outcome {}
    record Failure(Throwable cause) implements Outcome {}
    Outcome SUCCESS = new Success();
}
```

`Event.java`:

```java
package com.robsartin.marshal;

import java.util.List;

public sealed interface Event {
    record Completed(Node node, Outcome outcome, List<Mutation> mutations) implements Event {}
    record TimedOut(Node node) implements Event {}
    record Stop() implements Event {}
}
```

`BufferingExecutionContext.java`:

```java
package com.robsartin.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class BufferingExecutionContext implements ExecutionContext {
    private final Predicate<Node> completedView;
    private final List<Mutation> buffer = new ArrayList<>();

    public BufferingExecutionContext(Predicate<Node> completedView) {
        this.completedView = completedView;
    }

    @Override public boolean isCompleted(Node node) { return completedView.test(node); }
    @Override public void addNode(NodeSpec spec) { buffer.add(new Mutation.AddNode(spec)); }
    @Override public void removeNode(Node node) { buffer.add(new Mutation.RemoveNode(node)); }
    @Override public void addEdge(Node p, Node s) { buffer.add(new Mutation.AddEdge(p, s)); }
    @Override public void removeEdge(Node p, Node s) { buffer.add(new Mutation.RemoveEdge(p, s)); }
    @Override public void conflict(Node a, Node b) { buffer.add(new Mutation.AddConflict(a, b)); }

    @Override public void conflictGroup(Set<Node> nodes) {
        List<Node> list = new ArrayList<>(nodes);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                buffer.add(new Mutation.AddConflict(list.get(i), list.get(j)));
            }
        }
    }

    public List<Mutation> drain() {
        List<Mutation> out = List.copyOf(buffer);
        buffer.clear();
        return out;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests BufferingExecutionContextTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ExecutionContext + Mutation/Outcome/Event types + buffering context"
```

---

## Task 6: Marshal scheduler — deterministic single-threaded run (MVP)

**Files:**
- Create: `RunReport.java`, `Marshal.java`
- Test: `src/test/java/com/robsartin/marshal/support/InlineExecutor.java`, `MarshalRunTest.java`

**Interfaces:**
- Consumes: `GraphState`, `Selection`, `Event`, `Outcome`, `BufferingExecutionContext`.
- Produces:
  - `InlineExecutor implements Executor` — `execute(Runnable)` runs on the calling thread.
  - `record RunReport(Map<Node, Status> statuses, Map<Node, Throwable> failures)` with `Status statusOf(Node)`.
  - `Marshal(Executor ioLane, Executor cpuLane, int cpuPermits)` (no timeouts yet — added in Task 9); `Node register(NodeSpec)`, `void conflict(Node,Node)`, `void conflictGroup(Set<Node>)`, `RunReport run()`.
  - The run loop: promote ready nodes, `Selection.select`, dispatch to the lane executor (which posts a `Completed` event), drain events until quiescence, apply mutations at completion (Task 7 hardens this), build the report.

For the MVP this task ignores buffered mutations (drains but does not apply them) and treats every non-throwing `execute` as `COMPLETED`, every throwing one as `FAILED` with skip propagation. Mutation application is Task 7; timeouts are Task 9.

- [ ] **Step 1: Write InlineExecutor + the failing run test**

`support/InlineExecutor.java`:

```java
package com.robsartin.marshal.support;

import java.util.concurrent.Executor;

public final class InlineExecutor implements Executor {
    @Override public void execute(Runnable command) { command.run(); }
}
```

`MarshalRunTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class MarshalRunTest {
    private Marshal newMarshal() {
        Executor inline = new InlineExecutor();
        return new Marshal(inline, inline, 4);
    }

    @Test
    void runsDependencyChainInOrderAndReportsCompleted() {
        Marshal m = newMarshal();
        List<String> log = new java.util.ArrayList<>();
        Node a = ctx -> log.add("a");
        Node b = ctx -> log.add("b");
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(java.util.Set.of(a)).name("b").build());

        RunReport report = m.run();

        assertThat(log).containsExactly("a", "b");
        assertThat(report.statusOf(a)).isEqualTo(Status.COMPLETED);
        assertThat(report.statusOf(b)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void failedNodeSkipsItsDependentsButNotIndependentWork() {
        Marshal m = newMarshal();
        Node boom = ctx -> { throw new RuntimeException("boom"); };
        Node dependent = ctx -> {};
        Node independent = ctx -> {};
        m.register(NodeSpec.of(boom).name("boom").build());
        m.register(NodeSpec.of(dependent).predecessors(java.util.Set.of(boom)).build());
        m.register(NodeSpec.of(independent).build());

        RunReport report = m.run();

        assertThat(report.statusOf(boom)).isEqualTo(Status.FAILED);
        assertThat(report.statusOf(dependent)).isEqualTo(Status.SKIPPED);
        assertThat(report.statusOf(independent)).isEqualTo(Status.COMPLETED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests MarshalRunTest`
Expected: FAIL (`Marshal` / `RunReport` absent).

- [ ] **Step 3: Implement `RunReport` and `Marshal`**

`RunReport.java`:

```java
package com.robsartin.marshal;

import java.util.Map;

public record RunReport(Map<Node, Status> statuses, Map<Node, Throwable> failures) {
    public Status statusOf(Node n) { return statuses.get(n); }
}
```

`Marshal.java`:

```java
package com.robsartin.marshal;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

public final class Marshal {
    private final GraphState g = new GraphState();
    private final Executor ioLane;
    private final Executor cpuLane;
    private final int cpuPermits;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();

    public Marshal(Executor ioLane, Executor cpuLane, int cpuPermits) {
        this.ioLane = ioLane;
        this.cpuLane = cpuLane;
        this.cpuPermits = cpuPermits;
    }

    public Node register(NodeSpec spec) {
        // ensure predecessors/conflicts referenced by this spec exist as nodes first is the caller's job
        g.addNode(spec);
        return spec.behavior();
    }

    public void conflict(Node a, Node b) { g.addConflict(a, b); }

    public void conflictGroup(Set<Node> nodes) {
        List<Node> l = new ArrayList<>(nodes);
        for (int i = 0; i < l.size(); i++)
            for (int j = i + 1; j < l.size(); j++) g.addConflict(l.get(i), l.get(j));
    }

    public RunReport run() {
        int freeCpu = cpuPermits;
        int inFlight = 0;

        promoteReady();
        List<Selection.Dispatch> toStart = Selection.select(g, ready(), running(), freeCpu, Integer.MAX_VALUE);
        for (Selection.Dispatch d : toStart) { freeCpu = dispatch(d, freeCpu); inFlight++; }

        while (inFlight > 0) {
            Event ev = take();
            if (ev instanceof Event.Completed c) {
                inFlight--;
                Node n = c.node();
                if (g.status(n) != Status.RUNNING) continue;         // idempotency guard (used in Task 9)
                if (c.outcome() instanceof Outcome.Failure f) {
                    g.fail(n, Status.FAILED);
                    failures.put(n, f.cause());
                } else {
                    g.markCompleted(n);
                }
                if (g.spec(n).kind() == ExecutionKind.CPU) freeCpu++;

                promoteReady();
                List<Selection.Dispatch> next =
                    Selection.select(g, ready(), running(), freeCpu, Integer.MAX_VALUE);
                for (Selection.Dispatch d : next) { freeCpu = dispatch(d, freeCpu); inFlight++; }
            }
        }
        return report();
    }

    private final Map<Node, Throwable> failures = new IdentityHashMap<>();

    private int dispatch(Selection.Dispatch d, int freeCpu) {
        Node n = d.node();
        g.markRunning(n);
        Executor lane = d.lane() == ExecutionKind.CPU ? cpuLane : ioLane;
        lane.execute(() -> {
            BufferingExecutionContext ctx = new BufferingExecutionContext(g::isCompletedSafe);
            Outcome outcome;
            try {
                n.execute(ctx);
                outcome = Outcome.SUCCESS;
            } catch (Throwable t) {
                outcome = new Outcome.Failure(t);
            }
            events.add(new Event.Completed(n, outcome, ctx.drain()));
        });
        return d.lane() == ExecutionKind.CPU ? freeCpu - 1 : freeCpu;
    }

    private void promoteReady() {
        for (Node n : g.readyPromotable()) g.markReady(n);
    }

    private Set<Node> ready() {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : g.nodes()) if (g.status(n) == Status.READY) s.add(n);
        return s;
    }

    private Set<Node> running() {
        Set<Node> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : g.nodes()) if (g.status(n) == Status.RUNNING) s.add(n);
        return s;
    }

    private Event take() {
        try { return events.take(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    private RunReport report() {
        Map<Node, Status> statuses = new IdentityHashMap<>();
        for (Node n : g.nodes()) {
            Status s = g.status(n);
            statuses.put(n, s == Status.WAITING || s == Status.READY ? Status.UNREACHABLE : s);
        }
        return new RunReport(statuses, Map.copyOf(failures));
    }
}
```

Add to `GraphState` a null-safe read used by the context: `public boolean isCompletedSafe(Node n) { return status.get(n) == Status.COMPLETED; }`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests MarshalRunTest`
Expected: PASS. **This is the MVP milestone — a working deterministic executor.**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Marshal scheduler with deterministic single-threaded run (MVP)"
```

---

## Task 7: Apply buffered mutations at completion (with cycle/bad-batch rejection)

**Files:**
- Modify: `Marshal.java`
- Test: `MarshalMutationTest.java`

**Interfaces:**
- Consumes: `Mutation`, `GraphState.wouldIntroduceCycle`.
- Produces: `Marshal` applies `Completed.mutations()` **before** processing completion, atomically. If any mutation is invalid (unknown node reference, or an `AddEdge` that would introduce a cycle), the whole batch is rolled back and the completing node is marked `FAILED` (its dependents skip). Add `private void applyMutations(Node origin, List<Mutation> batch)` that validates first, then applies, throwing `MutationRejected` to signal rollback.

- [ ] **Step 1: Write the failing test**

`MarshalMutationTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.InlineExecutor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarshalMutationTest {
    private Marshal newMarshal() {
        var inline = new InlineExecutor();
        return new Marshal(inline, inline, 4);
    }

    @Test
    void nodeAddedDuringExecuteGetsScheduled() {
        Marshal m = newMarshal();
        boolean[] childRan = {false};
        Node child = ctx -> childRan[0] = true;
        Node parent = ctx -> ctx.addNode(NodeSpec.of(child).name("child").build());
        m.register(NodeSpec.of(parent).name("parent").build());

        RunReport r = m.run();

        assertThat(childRan[0]).isTrue();
        assertThat(r.statusOf(child)).isEqualTo(Status.COMPLETED);
    }

    @Test
    void cycleIntroducingMutationFailsTheOriginNode() {
        Marshal m = newMarshal();
        AtomicReference<Node> aRef = new AtomicReference<>();
        Node b = ctx -> {};
        // 'a' runs and adds edge b->a; since a->b already exists (b depends on a),
        // that closes the cycle a->b->a and must be rejected, failing a.
        Node a = ctx -> ctx.addEdge(b, aRef.get());
        aRef.set(a);
        m.register(NodeSpec.of(a).name("a").build());
        m.register(NodeSpec.of(b).predecessors(Set.of(a)).name("b").build());

        RunReport r = m.run();

        assertThat(r.statusOf(a)).isEqualTo(Status.FAILED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests MarshalMutationTest`
Expected: FAIL (mutations are drained but not applied in Task 6).

- [ ] **Step 3: Apply mutations in the completion handler**

In `Marshal.run()`'s `Completed` branch, before deciding success/failure, apply mutations for successful outcomes:

```java
if (c.outcome() instanceof Outcome.Failure f) {
    g.fail(n, Status.FAILED);
    failures.put(n, f.cause());
} else {
    try {
        applyMutations(n, c.mutations());
        g.markCompleted(n);
    } catch (MutationRejected rejected) {
        g.fail(n, Status.FAILED);
        failures.put(n, rejected);
    }
}
```

Add:

```java
private static final class MutationRejected extends RuntimeException {
    MutationRejected(String message) { super(message); }
}

private void applyMutations(Node origin, List<Mutation> batch) {
    // validate the whole batch against a trial copy first; reject atomically on any violation
    for (Mutation mu : batch) {
        if (mu instanceof Mutation.AddEdge e) {
            if (g.contains(e.predecessor()) && g.contains(e.successor())
                    && g.wouldIntroduceCycle(e.predecessor(), e.successor())) {
                throw new MutationRejected("edge would introduce a cycle: " + e);
            }
        }
    }
    for (Mutation mu : batch) {
        switch (mu) {
            case Mutation.AddNode a -> g.addNode(a.spec());
            case Mutation.RemoveNode rn -> g.removeNode(rn.node());
            case Mutation.AddEdge e -> g.addEdge(e.predecessor(), e.successor());
            case Mutation.RemoveEdge e -> g.removeEdge(e.predecessor(), e.successor());
            case Mutation.AddConflict cf -> g.addConflict(cf.a(), cf.b());
            case Mutation.AddConflictGroup cg -> conflictGroup(cg.nodes());
        }
    }
    promoteReady();
}
```

(Newly added nodes must be added before edges that reference them — `BufferingExecutionContext` preserves call order, and callers add nodes before wiring edges.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests MarshalMutationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: apply buffered graph mutations at completion; reject cycles/bad batches"
```

---

## Task 8: Timeouts interface + manual (deterministic) timeout handling

**Files:**
- Create: `Timeouts.java`, `ScheduledTimeouts.java`
- Create: `src/test/java/com/robsartin/marshal/support/ManualTimeouts.java`
- Modify: `Marshal.java` (accept a `Timeouts`, arm on dispatch, handle `TimedOut`)
- Test: `MarshalTimeoutTest.java`

**Interfaces:**
- Produces:
  - `interface Timeouts { void arm(Node node, Duration budget, Runnable onExpiry); void cancel(Node node); }`
  - `ScheduledTimeouts implements Timeouts, AutoCloseable` — backed by a single-thread `ScheduledExecutorService`.
  - `ManualTimeouts implements Timeouts` (test) — records armed budgets; `expire(Node)` fires that node's `onExpiry` deterministically.
  - `Marshal` new constructor `Marshal(Executor ioLane, Executor cpuLane, int cpuPermits, Timeouts timeouts)`. On dispatch, if `spec.timeout() != null`, `arm` a callback that interrupts the worker thread and posts `Event.TimedOut(node)`. On `TimedOut`, if still `RUNNING`, `g.fail(node, TIMED_OUT)`. Late `Completed` for a timed-out node is ignored by the existing status guard. On normal completion, `cancel` the timeout.

- [ ] **Step 1: Write ManualTimeouts + the failing test**

`support/ManualTimeouts.java`:

```java
package com.robsartin.marshal.support;

import com.robsartin.marshal.Node;
import com.robsartin.marshal.Timeouts;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ManualTimeouts implements Timeouts {
    private final Map<Node, Runnable> armed = new IdentityHashMap<>();

    @Override public synchronized void arm(Node node, Duration budget, Runnable onExpiry) {
        armed.put(node, onExpiry);
    }
    @Override public synchronized void cancel(Node node) { armed.remove(node); }

    public synchronized void expire(Node node) {
        Runnable r = armed.remove(node);
        if (r != null) r.run();
    }
}
```

`MarshalTimeoutTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.marshal.support.ManualTimeouts;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class MarshalTimeoutTest {
    @Test
    void timedOutNodeIsReportedAndDependentsSkipped() throws Exception {
        ManualTimeouts timeouts = new ManualTimeouts();
        Executor pool = Executors.newVirtualThreadPerTaskExecutor();
        Marshal m = new Marshal(pool, pool, 4, timeouts);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Node slow = ctx -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Node dependent = ctx -> {};
        m.register(NodeSpec.of(slow).timeout(Duration.ofSeconds(1)).name("slow").build());
        m.register(NodeSpec.of(dependent).predecessors(Set.of(slow)).build());

        // Run on a background thread so the test can trigger the timeout deterministically.
        var runner = Executors.newSingleThreadExecutor();
        var future = runner.submit(m::run);

        started.await();
        timeouts.expire(slow);              // fire the timeout: interrupts slow, posts TimedOut
        RunReport r = future.get();
        release.countDown();

        assertThat(r.statusOf(slow)).isEqualTo(Status.TIMED_OUT);
        assertThat(r.statusOf(dependent)).isEqualTo(Status.SKIPPED);
        runner.shutdownNow();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests MarshalTimeoutTest`
Expected: FAIL (`Timeouts` absent; 4-arg constructor absent).

- [ ] **Step 3: Implement Timeouts, ScheduledTimeouts, and wire Marshal**

`Timeouts.java`:

```java
package com.robsartin.marshal;

import java.time.Duration;

public interface Timeouts {
    void arm(Node node, Duration budget, Runnable onExpiry);
    void cancel(Node node);
}
```

`ScheduledTimeouts.java`:

```java
package com.robsartin.marshal;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;

public final class ScheduledTimeouts implements Timeouts, AutoCloseable {
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "marshal-timeout");
            t.setDaemon(true);
            return t;
        });
    private final Map<Node, ScheduledFuture<?>> armed = new IdentityHashMap<>();

    @Override public synchronized void arm(Node node, Duration budget, Runnable onExpiry) {
        armed.put(node, scheduler.schedule(onExpiry, budget.toMillis(), TimeUnit.MILLISECONDS));
    }
    @Override public synchronized void cancel(Node node) {
        ScheduledFuture<?> f = armed.remove(node);
        if (f != null) f.cancel(false);
    }
    @Override public void close() { scheduler.shutdownNow(); }
}
```

Wire `Marshal`:
- Add the field `private final Timeouts timeouts;` and the 4-arg constructor; keep the 3-arg constructor delegating to it with a no-op `Timeouts`.
- Track the worker `Thread` per running node in a `Map<Node, Thread> workerThreads` (identity), set inside the dispatched runnable via `Thread.currentThread()` before `execute`.
- On dispatch, after `markRunning`, if `spec.timeout() != null`: `timeouts.arm(n, spec.timeout(), () -> { Thread w = workerThreads.get(n); if (w != null) w.interrupt(); events.add(new Event.TimedOut(n)); });`
- In the event loop, handle `Event.TimedOut t`: `if (g.status(t.node()) == Status.RUNNING) { g.fail(t.node(), Status.TIMED_OUT); ... free CPU permit if CPU lane ...; promoteReady(); re-select/dispatch; } inFlight-- only when the corresponding Completed also arrives` — to keep `inFlight` correct, treat `TimedOut` as terminal for scheduling but still expect the late `Completed` (which the status guard ignores). Track a `Set<Node> terminated` and decrement `inFlight` on whichever of `{TimedOut, Completed}` arrives second for that node. Implement with a per-node counter: increment expected=1 at dispatch, and only decrement `inFlight` on the `Completed` event (timeouts don't remove the worker; the worker still posts `Completed` when it finally returns). So: `TimedOut` marks terminal + reselects but does NOT change `inFlight`; `Completed` always decrements `inFlight`.
- On normal `Completed`, call `timeouts.cancel(n)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests MarshalTimeoutTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: node timeouts via Timeouts abstraction; interrupt + TIMED_OUT + skip"
```

---

## Task 9: Multithreaded execution + factory for production defaults

**Files:**
- Modify: `Marshal.java` (thread-safe event intake is already a `BlockingQueue`; add a `Marshal.create()` factory)
- Test: `MarshalConcurrencyTest.java`

**Interfaces:**
- Produces:
  - `static Marshal create()` — IO lane = `Executors.newVirtualThreadPerTaskExecutor()`, CPU lane = `Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())`, `cpuPermits = availableProcessors()`, `timeouts = new ScheduledTimeouts()`.
  - `static Marshal create(int cpuPermits, Executor ioLane, Executor cpuLane, Timeouts timeouts)`.
  - No change to the scheduler algorithm — it already isolates all state on the run thread; workers only post events.

- [ ] **Step 1: Write the failing concurrency test**

`MarshalConcurrencyTest.java`:

```java
package com.robsartin.marshal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarshalConcurrencyTest {
    @Test
    void conflictingNodesNeverRunConcurrently() throws Exception {
        Marshal m = Marshal.create();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();

        Runnable body = () -> {
            int now = concurrent.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
        };
        Node a = ctx -> body.run();
        Node b = ctx -> body.run();
        Node c = ctx -> body.run();
        m.register(NodeSpec.of(a).build());
        m.register(NodeSpec.of(b).build());
        m.register(NodeSpec.of(c).build());
        m.conflictGroup(Set.of(a, b, c));               // mutually exclusive

        m.run();

        assertThat(maxObserved.get()).isEqualTo(1);     // never two at once
    }

    @Test
    void independentNodesRunInParallel() throws Exception {
        Marshal m = Marshal.create();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);

        Node[] ns = new Node[4];
        for (int i = 0; i < ns.length; i++) {
            ns[i] = ctx -> {
                int now = concurrent.incrementAndGet();
                maxObserved.accumulateAndGet(now, Math::max);
                try { gate.await(200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                concurrent.decrementAndGet();
            };
            m.register(NodeSpec.of(ns[i]).kind(ExecutionKind.IO).build());
        }
        m.run();
        assertThat(maxObserved.get()).isGreaterThan(1);  // genuine parallelism on the IO lane
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests MarshalConcurrencyTest`
Expected: FAIL (`Marshal.create` absent).

- [ ] **Step 3: Add the factory methods**

```java
public static Marshal create() {
    return new Marshal(
        Executors.newVirtualThreadPerTaskExecutor(),
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()),
        Runtime.getRuntime().availableProcessors(),
        new ScheduledTimeouts());
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests MarshalConcurrencyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: production Marshal.create() factory; verify conflict exclusion + parallelism"
```

---

## Task 10: Architecture tests (Invariant rule, no-Spring rule)

**Files:**
- Test: `ArchitectureTest.java`

**Interfaces:**
- Consumes: all `main` classes.
- Produces: ArchUnit rules — (1) no class in the library depends on any `org.springframework..` package; (2) every non-record, non-enum, non-interface class in `com.robsartin.marshal` whose name ends in `State` (mutable owner-state classes) implements `Invariant`. (Scoped to the `State` suffix to encode the spec's "mutable stateful classes" boundary without forcing the marker onto value types or the scheduler.)

- [ ] **Step 1: Write the failing architecture test**

```java
package com.robsartin.marshal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private final JavaClasses classes =
        new ClassFileImporter().importPackages("com.robsartin.marshal");

    @Test
    void noSpringDependency() {
        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(classes);
    }

    @Test
    void mutableStateClassesImplementInvariant() {
        classes().that().haveSimpleNameEndingWith("State")
            .should().implement(Invariant.class)
            .check(classes);
    }
}
```

- [ ] **Step 2: Run to verify it passes (green by construction if GraphState is correct)**

Run: `./gradlew test --tests ArchitectureTest`
Expected: PASS. If `mutableStateClassesImplementInvariant` fails, it caught a real gap — make the offending `*State` class implement `Invariant`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: ArchUnit rules — no Spring dependency, *State implements Invariant"
```

---

## Task 11: ADRs, coverage gate, and documentation

**Files:**
- Create: `docs/adr/0001-record-architecture-decisions.md` … `0005-invariants-first-class.md` (via the `adr-toolkit` plugin)
- Create: `docs/dev/architecture.md`, `README.md` usage section
- Modify: nothing in `src` unless coverage verification reveals gaps

**Interfaces:**
- Produces: the five ADRs from the spec Section 9; developer architecture doc; a user-facing README usage example; passing `jacocoTestCoverageVerification`.

- [ ] **Step 1: Scaffold ADRs**

Use the `adr-toolkit` skill/plugin to create `docs/adr/` with the universal baseline plus a Java/library pack, then author ADRs 0001–0005 with the decisions and rationale copied from the spec's decision table. Each ADR: Context / Decision / Consequences.

- [ ] **Step 2: Write the developer architecture doc**

`docs/dev/architecture.md`: the single-owner model, the event loop, the mutation-commit protocol, the invariant discipline and how to run the property test, and the two-lane executor. Link back to the spec.

- [ ] **Step 3: Write the README usage example**

Add to `README.md` a minimal runnable example:

```java
Marshal m = Marshal.create();
Node fetch = ctx -> download();
Node parse = ctx -> parse();
m.register(NodeSpec.of(fetch).priority(10).name("fetch").build());
m.register(NodeSpec.of(parse).predecessors(Set.of(fetch)).name("parse").build());
RunReport report = m.run();
```

Plus a short "Using marshal from Spring Boot" note: wrap `Marshal.create()` (or a custom-executor constructor) in a `@Bean`; the library imports nothing from Spring.

- [ ] **Step 4: Run the full gate**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew clean check`
Expected: PASS — `spotlessCheck`, all tests with `-ea`, `jacocoTestCoverageVerification` (line > 80%, branch > 65%), and the ArchUnit rules. If coverage falls short, add targeted tests for the uncovered branches (e.g. `removeEdge` on a completed predecessor, `conflictGroup` sugar, idempotent `addNode`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: ADRs 0001-0005, developer architecture doc, README usage; enforce coverage gate"
```

---

## Self-review notes (for the executor)

- **Spec coverage:** predecessors/AND-join (Tasks 2, 2b, 6) · conflicts as `Map<Node,Set<Node>>` + `conflict`/`conflictGroup` (Tasks 2b, 5, 9) · priority (Task 4) · timeouts interrupt+report (Task 8) · two lanes + permits (Tasks 4, 9) · single-owner scheduler (Task 6) · mutation-commit + cycle rejection (Task 7) · skip-dependents failure policy (Tasks 2b, 6) · Invariant discipline + jqwik oracle + ArchUnit (Tasks 2–3, 10) · identity by reference (throughout) · ADRs + gates + docs (Task 11).
- **Deferred (spec §11) intentionally absent:** ANY/N_OF joins, named conflict resources, sealed `ExecutionKind`, bucket priority queue, blocking-within-execute, large-clique sentinel.
- **Milestones:** Task 6 = deterministic MVP; Task 9 = production multithreaded; Task 11 = full gates green.
