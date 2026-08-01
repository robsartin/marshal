package com.robsartin.marshal;

import java.util.List;

public sealed interface Event {
    record Completed(Node node, Outcome outcome, List<Mutation> mutations) implements Event {}

    record TimedOut(Node node) implements Event {}

    record Stop() implements Event {}
}
