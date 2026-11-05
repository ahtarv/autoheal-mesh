package com.autoheal.core;

import java.time.Instant;
import java.util.List;

public record RemediationResult(
        Instant executedAt,
        FailureSignature triggeredBy,
        PlaybookType playbook,
        boolean success,
        String actionSummary,
        List<String> stepsExecuted,
        int attemptNumber
) {
    public enum PlaybookType {
        THREAD_DUMP_AND_RESTART,
        CONNECTION_POOL_FLUSH,
        CPU_THROTTLE,
        GC_FORCE_AND_MONITOR,
        EXCEPTION_RATE_LIMIT,
        ESCALATE_TO_HUMAN
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] Playbook=%s Success=%b Attempt=%d | %s",
                executedAt, playbook, success, attemptNumber, actionSummary
        );
    }
}
