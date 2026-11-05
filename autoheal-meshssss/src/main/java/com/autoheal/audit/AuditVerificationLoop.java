package com.autoheal.audit;

import com.autoheal.core.RemediationResult;
import com.autoheal.core.TelemetrySnapshot;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class AuditVerificationLoop {

    private static final Logger LOG = Logger.getLogger(AuditVerificationLoop.class.getName());

    private final long verificationWindowMs;
    private final long checkIntervalMs;
    private final Consumer<RemediationResult> onEscalate;

    private final Deque<TelemetrySnapshot> recentSnapshots = new ConcurrentLinkedDeque<>();
    private final int maxSnapshots = 60;

    private volatile double baselineHeapPct    = -1;
    private volatile double baselineCpuPct     = -1;
    private volatile long   baselineExceptions = -1;

    public AuditVerificationLoop(
            long verificationWindowMs,
            long checkIntervalMs,
            Consumer<RemediationResult> onEscalate
    ) {
        this.verificationWindowMs = verificationWindowMs;
        this.checkIntervalMs      = checkIntervalMs;
        this.onEscalate           = onEscalate;
    }

    public void ingestSnapshot(TelemetrySnapshot snap) {
        recentSnapshots.addLast(snap);
        while (recentSnapshots.size() > maxSnapshots) {
            recentSnapshots.pollFirst();
        }
    }

    public void captureBaseline() {
        TelemetrySnapshot latest = recentSnapshots.peekLast();
        if (latest != null) {
            baselineHeapPct    = latest.heapUsagePercent();
            baselineCpuPct     = latest.cpuUsagePercent();
            baselineExceptions = latest.unhandledExceptionCount();
            LOG.info(String.format("[Audit] Baseline: Heap=%.1f%% CPU=%.1f%% Exceptions=%d",
                    baselineHeapPct, baselineCpuPct, baselineExceptions));
        }
    }

    public void verifyAsync(RemediationResult result) {
        Thread.ofVirtual().name("audit-verify-" + result.playbook()).start(() -> verify(result));
    }

    private void verify(RemediationResult result) {
        long deadline = System.currentTimeMillis() + verificationWindowMs;
        boolean recovered = false;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            TelemetrySnapshot latest = recentSnapshots.peekLast();
            if (latest == null) continue;

            if (isRecovered(latest)) {
                recovered = true;
                LOG.info(String.format("[Audit] Recovery confirmed after %s — Heap=%.1f%% CPU=%.1f%%",
                        result.playbook(), latest.heapUsagePercent(), latest.cpuUsagePercent()));
                break;
            } else {
                LOG.warning(String.format("[Audit] Still degraded — Heap=%.1f%% CPU=%.1f%% Exceptions=%d",
                        latest.heapUsagePercent(), latest.cpuUsagePercent(),
                        latest.unhandledExceptionCount()));
            }
        }

        if (!recovered) {
            LOG.severe("[Audit] Verification failed, escalating.");
            onEscalate.accept(result);
        }
    }

    private boolean isRecovered(TelemetrySnapshot snap) {
        boolean heapOk = baselineHeapPct < 0 || snap.heapUsagePercent() <= baselineHeapPct * 1.10;
        boolean cpuOk  = baselineCpuPct  < 0 || snap.cpuUsagePercent()  <= baselineCpuPct  * 1.15;
        boolean exOk   = baselineExceptions < 0
                || snap.unhandledExceptionCount() <= baselineExceptions * 1.5 + 1;
        return heapOk && cpuOk && exOk;
    }

    public int getRecentSnapshotCount() {
        return recentSnapshots.size();
    }
}
