package com.robsartin.marshal;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record NodeSpec(
        Node behavior,
        int priority,
        Duration timeout, // null == no timeout
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

        private Builder(Node behavior) {
            this.behavior = behavior;
        }

        public Builder priority(int p) {
            this.priority = p;
            return this;
        }

        public Builder timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        public Builder kind(ExecutionKind k) {
            this.kind = k;
            return this;
        }

        public Builder predecessors(Set<Node> p) {
            this.predecessors = p;
            return this;
        }

        public Builder conflicts(Set<Node> c) {
            this.conflicts = c;
            return this;
        }

        public Builder name(String n) {
            this.name = n;
            return this;
        }

        public NodeSpec build() {
            return new NodeSpec(behavior, priority, timeout, kind, predecessors, conflicts, name);
        }
    }
}
