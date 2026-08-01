package com.robsartin.marshal;

public interface Invariant {
    /** @throws IllegalStateException if the representation invariant is violated. */
    void invariant();
}
