package com.robsartin.marshal.support;

import java.util.concurrent.Executor;

public final class InlineExecutor implements Executor {
    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
