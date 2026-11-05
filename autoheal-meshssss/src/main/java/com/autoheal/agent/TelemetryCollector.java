package com.autoheal.agent;

import com.autoheal.core.EventBus;
import com.autoheal.core.TelemetrySnapshot;

import java.lang.management.*;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class TelemetryCollector {

    private static final Logger LOG = Logger.getLogger(TelemetryCollector.class.getName());

    private final EventBus<TelemetrySnapshot> bus;
    private final long pollIntervalMs;
    private final AtomicLong exceptionCounter = new AtomicLong(0);

    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final OperatingSystemMXBean osBean;

    private ScheduledExecutorService scheduler;

    public TelemetryCollector(EventBus<TelemetrySnapshot> bus, long pollIntervalMs) {
        this.bus = bus;
        this.pollIntervalMs = pollIntervalMs;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            exceptionCounter.incrementAndGet();
            LOG.warning("[TelemetryCollector] Uncaught exception on " + thread.getName() + ": " + throwable.getMessage());
        });
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("telemetry-collector").factory()
        );
        scheduler.scheduleAtFixedRate(this::collectAndPublish, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void recordException() {
        exceptionCounter.incrementAndGet();
    }

    private void collectAndPublish() {
        try {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            long heapUsed = heap.getUsed();
            long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

            int activeThreads = threadBean.getThreadCount();
            long[] deadlocked = threadBean.findDeadlockedThreads();
            int deadlockedCount = deadlocked != null ? deadlocked.length : 0;

            double cpuLoad = getCpuLoad();
            long exceptions = exceptionCounter.get();
            int openFds = getOpenFileDescriptors();

            TelemetrySnapshot snapshot = TelemetrySnapshot.of(
                    Instant.now(), cpuLoad, heapUsed, heapMax,
                    activeThreads, deadlockedCount, exceptions, openFds
            );

            bus.publish(snapshot);

        } catch (Exception e) {
            LOG.severe("[TelemetryCollector] Collection error: " + e.getMessage());
        }
    }

    private double getCpuLoad() {
        try {
            var method = osBean.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            double load = (double) method.invoke(osBean);
            return load < 0 ? 0.0 : load * 100.0;
        } catch (Exception e) {
            double avg = osBean.getSystemLoadAverage();
            return avg < 0 ? 0.0 : Math.min(avg * 25.0, 100.0);
        }
    }

    private int getOpenFileDescriptors() {
        try {
            var method = osBean.getClass().getMethod("getOpenFileDescriptorCount");
            method.setAccessible(true);
            return ((Long) method.invoke(osBean)).intValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
