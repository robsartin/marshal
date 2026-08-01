package com.robsartin.marshal;

public sealed interface Mutation {
    record AddNode(NodeSpec spec) implements Mutation {}

    record RemoveNode(Node node) implements Mutation {}

    record AddEdge(Node predecessor, Node successor) implements Mutation {}

    record RemoveEdge(Node predecessor, Node successor) implements Mutation {}

    record AddConflict(Node a, Node b) implements Mutation {}
}
