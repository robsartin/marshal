package com.robsartin.marshal;

@FunctionalInterface
public interface Node {
    void execute(ExecutionContext ctx);
}
