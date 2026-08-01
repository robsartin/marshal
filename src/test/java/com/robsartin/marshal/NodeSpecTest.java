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
        preds.clear(); // must not affect the spec (defensive copy)
        assertThat(spec.predecessors()).containsExactly(a);
    }
}
