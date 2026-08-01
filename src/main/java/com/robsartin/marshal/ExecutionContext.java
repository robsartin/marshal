package com.robsartin.marshal;

/** Handle a running node uses to read state and buffer graph mutations. Expanded in Task 5. */
public interface ExecutionContext {
    boolean isCompleted(Node node);
}
