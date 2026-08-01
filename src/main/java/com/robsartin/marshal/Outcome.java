package com.robsartin.marshal;

public sealed interface Outcome {
    record Success() implements Outcome {}

    record Failure(Throwable cause) implements Outcome {}

    Outcome SUCCESS = new Success();
}
