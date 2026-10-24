package com.alexs.jobagent.source.service;

import java.time.Duration;

@FunctionalInterface
public interface RetryWait {
    void await(Duration delay) throws InterruptedException;
}
