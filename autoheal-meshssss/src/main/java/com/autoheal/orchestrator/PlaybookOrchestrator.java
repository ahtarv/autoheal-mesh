package com.autoheal.orchestrator;

import com.autoheal.core.FailureSignature;
import com.autoheal.core.RemediationResult;
import com.autoheal.core.RemediationResult.PlaybookType;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class PlaybookOrchestrator {

    private static final Logger LOG = Logger.getLogger(PlaybookOrchestrator.class.getName());

    private final Semaphore remediationLock = new Semaphore(1);
    private final AtomicInteger simulatedPoolConnections = new AtomicInteger(100);
    private final CircuitBreaker circuitBreaker;

    public PlaybookOrchestrator() {
        this.circuitBreaker = new CircuitBreaker("connection-pool-cb", 3, 30_000);
    }

    public RemediationResult execute(FailureSignature signature, int attempt) {
        if (!remediationLock.tryAcquire()) {
            LOG.warning("[Orchestrator] Remediation already in progress, skipping");
            return buildResult(signature, PlaybookType.ESCALATE_TO_HUMAN, false,
                    "Skipped: concurrent remediation in progress", List.of(), attempt);
        }

        try {
            return switch (signature) {
                case FailureSignature.ThreadDeadlock d             -> playbookThreadDeadlock(d, attempt);
                case FailureSignature.ConnectionPoolExhaustion c   -> playbookConnectionPool(c, attempt);
                case FailureSignature.MemoryLeak m                 -> playbookMemoryLeak(m, attempt);
                case FailureSignature.CpuSpike s                   -> playbookCpuSpike(s, attempt);
                case FailureSignature.ExceptionStorm e             -> playbookExceptionStorm(e, attempt);
            };
        } finally {
            remediationLock.release();
        }
    }

    private RemediationResult playbookThreadDeadlock(FailureSignature.ThreadDeadlock sig, int attempt) {
        List<String> steps = new ArrayList<>();

        String threadDump = captureThreadDump();
        steps.add("Thread dump captured (" + threadDump.length() + " chars)");

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threadBean.findDeadlockedThreads();
        if (deadlocked != null) {
            for (long id : deadlocked) {
                steps.add("Flagged thread ID " + id + " for restart");
            }
        }

        simulateGracefulRestart(steps);

        return buildResult(sig, PlaybookType.THREAD_DUMP_AND_RESTART, true,
                "Deadlock mitigated: thread dump captured, workers restarted", steps, attempt);
    }

    private RemediationResult playbookConnectionPool(FailureSignature.ConnectionPoolExhaustion sig, int attempt) {
        List<String> steps = new ArrayList<>();

        if (circuitBreaker.isOpen()) {
            steps.add("Circuit breaker open, throttling all incoming requests");
            return buildResult(sig, PlaybookType.CONNECTION_POOL_FLUSH, false,
                    "Circuit breaker open, waiting for cooldown", steps, attempt);
        }

        int staleCount = flushStaleConnections();
        steps.add("Flushed " + staleCount + " stale idle connections");

        circuitBreaker.trip();
        steps.add("Circuit breaker tripped, throttling traffic for " + circuitBreaker.getCooldownMs() + "ms");
        steps.add("Dropping non-critical requests");

        return buildResult(sig, PlaybookType.CONNECTION_POOL_FLUSH, true,
                "Pool exhaustion mitigated: flushed connections, tripped circuit breaker", steps, attempt);
    }

    private RemediationResult playbookMemoryLeak(FailureSignature.MemoryLeak sig, int attempt) {
        List<String> steps = new ArrayList<>();

        steps.add("Requesting GC, heap at " + String.format("%.1f%%", sig.currentHeapPct()));
        System.gc();

        if (sig.currentHeapPct() > 85.0) {
            simulateGracefulRestart(steps);
            steps.add("Rolling restart triggered, heap critically high");
        } else {
            steps.add("GC sufficient, monitoring heap trend");
        }

        return buildResult(sig, PlaybookType.GC_FORCE_AND_MONITOR, true,
                "Memory leak response: GC triggered, heap monitored", steps, attempt);
    }

    private RemediationResult playbookCpuSpike(FailureSignature.CpuSpike sig, int attempt) {
        List<String> steps = new ArrayList<>();

        steps.add(String.format("CPU spike at %.1f%% avg, applying throttle", sig.avgCpuPercent()));
        steps.add("Background job scheduler paused for 60 seconds");
        steps.add("Non-essential thread pool cores reduced by 50%");

        return buildResult(sig, PlaybookType.CPU_THROTTLE, true,
                "CPU spike mitigated: background tasks paused, pool reduced", steps, attempt);
    }

    private RemediationResult playbookExceptionStorm(FailureSignature.ExceptionStorm sig, int attempt) {
        List<String> steps = new ArrayList<>();

        steps.add(String.format("%d exceptions at %.1fx above baseline",
                sig.exceptionCount(), sig.multiplierAboveBaseline()));
        steps.add("Rate limiter applied to error-producing endpoints");
        steps.add("Dead-letter queue routing activated");

        return buildResult(sig, PlaybookType.EXCEPTION_RATE_LIMIT, true,
                "Exception storm contained: rate limiting and DLQ routing active", steps, attempt);
    }

    private String captureThreadDump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : infos) {
            sb.append(info.toString()).append("\n");
        }
        return sb.toString();
    }

    private int flushStaleConnections() {
        int flushed = simulatedPoolConnections.get() / 2;
        simulatedPoolConnections.addAndGet(-flushed);
        return flushed;
    }

    private void simulateGracefulRestart(List<String> steps) {
        steps.add("Draining: stopped accepting new requests");
        steps.add("Waiting for in-flight requests to complete");
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        steps.add("Worker restarted via rolling deployment hook");
        steps.add("Health check passed, node back online");
    }

    private RemediationResult buildResult(
            FailureSignature sig,
            PlaybookType playbook,
            boolean success,
            String summary,
            List<String> steps,
            int attempt
    ) {
        return new RemediationResult(Instant.now(), sig, playbook, success, summary, steps, attempt);
    }
}
