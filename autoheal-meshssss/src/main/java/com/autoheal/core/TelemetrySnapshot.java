package com.autoheal.core;

import java.time.Instant;

public record TelemetrySnapshot(
        Instant timestamp,
        double cpuUsagePercent,
        long heapUsedBytes,
        long heapMaxBytes,
        int activeThreadCount,
        int deadlockedThreadCount,
        long unhandledExceptionCount,
        int openFileDescriptors,
        double heapUsagePercent
) {
    public TelemetrySnapshot {
        if (cpuUsagePercent < 0 || cpuUsagePercent > 100)
            throw new IllegalArgumentException("CPU usage out of range: " + cpuUsagePercent);
        if (heapMaxBytes <= 0)
            throw new IllegalArgumentException("heapMaxBytes must be > 0");
    }

    public static TelemetrySnapshot of(
            Instant timestamp,
            double cpuUsagePercent,
            long heapUsedBytes,
            long heapMaxBytes,
            int activeThreadCount,
            int deadlockedThreadCount,
            long unhandledExceptionCount,
            int openFileDescriptors
    ) {
        double heapPct = heapMaxBytes > 0 ? (heapUsedBytes * 100.0 / heapMaxBytes) : 0.0;
        return new TelemetrySnapshot(
                timestamp, cpuUsagePercent, heapUsedBytes, heapMaxBytes,
                activeThreadCount, deadlockedThreadCount,
                unhandledExceptionCount, openFileDescriptors, heapPct
        );
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] CPU=%.1f%% Heap=%.1f%% Threads=%d Deadlocks=%d Exceptions=%d",
                timestamp, cpuUsagePercent, heapUsagePercent,
                activeThreadCount, deadlockedThreadCount, unhandledExceptionCount
        );
    }
}
