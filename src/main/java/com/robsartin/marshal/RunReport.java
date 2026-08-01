package com.robsartin.marshal;

import java.util.Map;

public record RunReport(Map<Node, Status> statuses, Map<Node, Throwable> failures) {
    public Status statusOf(Node n) {
        return statuses.get(n);
    }
}
