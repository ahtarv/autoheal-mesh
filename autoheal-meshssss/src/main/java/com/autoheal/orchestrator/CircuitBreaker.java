package com.autoheal.orchestrator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class CircuitBreaker {

    private static final Logger LOG = Logger.getLogger(CircuitBreaker.class.getName());

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public boolean isOpen() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
                state = State.HALF_OPEN;
                LOG.info("[CircuitBreaker:" + name + "] OPEN -> HALF_OPEN");
                return false;
            }
            return true;
        }
        return false;
    }

    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold) {
            trip();
        }
    }

    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            reset();
        }
        failureCount.set(0);
    }

    public void trip() {
        if (state != State.OPEN) {
            state = State.OPEN;
            openedAt.set(System.currentTimeMillis());
            LOG.warning("[CircuitBreaker:" + name + "] TRIPPED -> OPEN");
        }
    }

    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        LOG.info("[CircuitBreaker:" + name + "] RESET -> CLOSED");
    }

    public State getState() { return state; }
    public long getCooldownMs() { return cooldownMs; }
    public String getName() { return name; }
}
