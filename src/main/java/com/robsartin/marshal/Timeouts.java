package com.robsartin.marshal;

import java.time.Duration;

/**
 * Arms and cancels per-node watchdog callbacks. {@link Marshal} arms a timeout on dispatch when
 * {@link NodeSpec#timeout()} is non-null; the callback interrupts the node's worker thread and
 * posts an {@link Event.TimedOut}. {@link Marshal} cancels the timeout when the node completes
 * normally.
 */
public interface Timeouts {
    void arm(Node node, Duration budget, Runnable onExpiry);

    void cancel(Node node);
}
