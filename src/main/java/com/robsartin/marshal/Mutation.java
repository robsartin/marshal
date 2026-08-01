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
