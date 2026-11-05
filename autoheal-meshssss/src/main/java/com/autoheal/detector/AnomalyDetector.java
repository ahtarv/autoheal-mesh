package com.autoheal.detector;

import com.autoheal.core.EventBus;
import com.autoheal.core.FailureSignature;
import com.autoheal.core.FailureSignature.Severity;
import com.autoheal.core.TelemetrySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public final class AnomalyDetector {

    private static final Logger LOG = Logger.getLogger(AnomalyDetector.class.getName());

    private final int windowSize;
    private final double memLeakSlopeThreshold;
    private final double cpuSpikeThreshold;
    private final int poolCeiling;
    private final double exceptionBaselineMultiplier;

    private final SlidingWindow heapWindow;
    private final SlidingWindow cpuWindow;
    private final SlidingWindow exceptionWindow;

    private double exceptionBaseline = -1;

    private final EventBus<List<FailureSignature>> anomalyBus;

    public AnomalyDetector(
            EventBus<List<FailureSignature>> anomalyBus,
            int windowSize,
            double memLeakSlopeThreshold,
            double cpuSpikeThreshold,
            int poolCeiling,
            double exceptionBaselineMultiplier
    ) {
        this.anomalyBus = anomalyBus;
        this.windowSize = windowSize;
        this.memLeakSlopeThreshold = memLeakSlopeThreshold;
        this.cpuSpikeThreshold = cpuSpikeThreshold;
        this.poolCeiling = poolCeiling;
        this.exceptionBaselineMultiplier = exceptionBaselineMultiplier;

        this.heapWindow      = new SlidingWindow(windowSize);
        this.cpuWindow       = new SlidingWindow(windowSize);
        this.exceptionWindow = new SlidingWindow(windowSize);
    }

    public void process(TelemetrySnapshot snap) {
        heapWindow.add(snap.heapUsagePercent());
        cpuWindow.add(snap.cpuUsagePercent());
        exceptionWindow.add((double) snap.unhandledExceptionCount());

        List<FailureSignature> detected = new ArrayList<>();

        detectMemoryLeak(snap).ifPresent(detected::add);
        detectDeadlock(snap).ifPresent(detected::add);
        detectPoolExhaustion(snap).ifPresent(detected::add);
        detectCpuSpike().ifPresent(detected::add);
        detectExceptionStorm().ifPresent(detected::add);

        if (!detected.isEmpty()) {
            detected.forEach(sig -> LOG.warning("[AnomalyDetector] " + sig.description()));
            anomalyBus.publish(detected);
        }
    }

    private Optional<FailureSignature> detectMemoryLeak(TelemetrySnapshot snap) {
        if (!heapWindow.isFull()) return Optional.empty();

        double slope = heapWindow.slope();
        if (slope >= memLeakSlopeThreshold) {
            Severity sev = slope >= memLeakSlopeThreshold * 3 ? Severity.CRITICAL : Severity.ERROR;
            return Optional.of(new FailureSignature.MemoryLeak(slope, snap.heapUsagePercent(), sev));
        }
        return Optional.empty();
    }

    private Optional<FailureSignature> detectDeadlock(TelemetrySnapshot snap) {
        if (snap.deadlockedThreadCount() > 0) {
            long[] ids = new long[snap.deadlockedThreadCount()];
            for (int i = 0; i < ids.length; i++) ids[i] = -1L;
            return Optional.of(new FailureSignature.ThreadDeadlock(
                    snap.deadlockedThreadCount(), ids, Severity.CRITICAL));
        }
        return Optional.empty();
    }

    private Optional<FailureSignature> detectPoolExhaustion(TelemetrySnapshot snap) {
        if (snap.activeThreadCount() >= poolCeiling) {
            Severity sev = snap.activeThreadCount() >= poolCeiling * 1.2
                    ? Severity.CRITICAL : Severity.ERROR;
            return Optional.of(new FailureSignature.ConnectionPoolExhaustion(
                    snap.activeThreadCount(), poolCeiling, sev));
        }
        return Optional.empty();
    }

    private Optional<FailureSignature> detectCpuSpike() {
        if (!cpuWindow.isFull()) return Optional.empty();
        double avg = cpuWindow.average().orElse(0.0);
        if (avg >= cpuSpikeThreshold) {
            Severity sev = avg >= 95.0 ? Severity.CRITICAL : Severity.ERROR;
            return Optional.of(new FailureSignature.CpuSpike(avg, sev));
        }
        return Optional.empty();
    }

    private Optional<FailureSignature> detectExceptionStorm() {
        if (!exceptionWindow.isFull()) return Optional.empty();

        double currentAvg = exceptionWindow.average().orElse(0.0);

        if (exceptionBaseline < 0) {
            exceptionBaseline = currentAvg;
            return Optional.empty();
        }

        if (exceptionBaseline == 0) return Optional.empty();

        double multiplier = currentAvg / exceptionBaseline;
        if (multiplier >= exceptionBaselineMultiplier) {
            return Optional.of(new FailureSignature.ExceptionStorm(
                    (long) currentAvg, multiplier,
                    multiplier >= exceptionBaselineMultiplier * 2 ? Severity.CRITICAL : Severity.WARN));
        }
        return Optional.empty();
    }

    public SlidingWindow getHeapWindow()  { return heapWindow; }
    public SlidingWindow getCpuWindow()   { return cpuWindow; }
}
