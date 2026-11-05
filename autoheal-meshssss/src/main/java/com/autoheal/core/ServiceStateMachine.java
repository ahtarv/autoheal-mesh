package com.autoheal.core;

public final class ServiceStateMachine {

    public enum State {
        HEALTHY,
        DEGRADED,
        REMEDIATING,
        ESCALATED
    }

    private volatile State currentState = State.HEALTHY;
    private volatile int remediationAttempts = 0;
    private static final int MAX_AUTO_ATTEMPTS = 2;

    public synchronized State getState() {
        return currentState;
    }

    public synchronized void onAnomalyDetected() {
        if (currentState == State.HEALTHY || currentState == State.DEGRADED) {
            transitionTo(State.DEGRADED);
        }
    }

    public synchronized void onRemediationStarted() {
        if (currentState == State.DEGRADED) {
            remediationAttempts++;
            transitionTo(State.REMEDIATING);
        }
    }

    public synchronized void onRemediationSucceeded() {
        if (currentState == State.REMEDIATING) {
            remediationAttempts = 0;
            transitionTo(State.HEALTHY);
        }
    }

    public synchronized boolean onRemediationFailed() {
        if (currentState == State.REMEDIATING) {
            if (remediationAttempts >= MAX_AUTO_ATTEMPTS) {
                transitionTo(State.ESCALATED);
                return true;
            } else {
                transitionTo(State.DEGRADED);
            }
        }
        return false;
    }

    public synchronized void manualReset() {
        remediationAttempts = 0;
        transitionTo(State.HEALTHY);
    }

    public int getRemediationAttempts() {
        return remediationAttempts;
    }

    private void transitionTo(State next) {
        System.out.printf("[StateMachine] %s -> %s%n", currentState, next);
        this.currentState = next;
    }
}
