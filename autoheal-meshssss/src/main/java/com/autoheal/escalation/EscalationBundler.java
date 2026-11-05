package com.autoheal.escalation;

import com.autoheal.core.FailureSignature;
import com.autoheal.core.RemediationResult;
import com.autoheal.core.TelemetrySnapshot;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.logging.Logger;

public final class EscalationBundler {

    private static final Logger LOG = Logger.getLogger(EscalationBundler.class.getName());
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private final String outputDir;

    public EscalationBundler(String outputDir) {
        this.outputDir = outputDir;
    }

    public Path generateBundle(RemediationResult result, Collection<TelemetrySnapshot> snapshots) {
        String bundleName = "incident_" + TS_FMT.format(Instant.now());
        Path bundlePath = Paths.get(outputDir, bundleName);

        try {
            Files.createDirectories(bundlePath);
            writeThreadDump(bundlePath);
            writeTelemetryCSV(bundlePath, snapshots);
            writeRemediationLog(bundlePath, result);
            writeRootCauseHypothesis(bundlePath, result.triggeredBy());
            writeIncidentSummary(bundlePath, result, snapshots);
            LOG.severe("[Escalation] Bundle written to: " + bundlePath.toAbsolutePath());
        } catch (IOException e) {
            LOG.severe("[Escalation] Failed to write bundle: " + e.getMessage());
        }

        return bundlePath;
    }

    private void writeThreadDump(Path dir) throws IOException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("thread_dump.txt")))) {
            w.println("=== THREAD DUMP === " + Instant.now());
            w.println("Total threads: " + bean.getThreadCount());
            long[] deadlocked = bean.findDeadlockedThreads();
            w.println("Deadlocked threads: " + (deadlocked != null ? deadlocked.length : 0));
            w.println();
            for (ThreadInfo info : infos) {
                w.println(info.toString());
            }
        }
    }

    private void writeTelemetryCSV(Path dir, Collection<TelemetrySnapshot> snapshots) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("telemetry.csv")))) {
            w.println("timestamp,cpu_pct,heap_pct,heap_used_mb,active_threads,deadlocked,exceptions");
            for (TelemetrySnapshot s : snapshots) {
                w.printf("%s,%.2f,%.2f,%.1f,%d,%d,%d%n",
                        s.timestamp(),
                        s.cpuUsagePercent(),
                        s.heapUsagePercent(),
                        s.heapUsedBytes() / 1_048_576.0,
                        s.activeThreadCount(),
                        s.deadlockedThreadCount(),
                        s.unhandledExceptionCount());
            }
        }
    }

    private void writeRemediationLog(Path dir, RemediationResult result) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("remediation_log.txt")))) {
            w.println("=== REMEDIATION LOG ===");
            w.println("Playbook    : " + result.playbook());
            w.println("Executed At : " + result.executedAt());
            w.println("Attempt #   : " + result.attemptNumber());
            w.println("Success     : " + result.success());
            w.println("Summary     : " + result.actionSummary());
            w.println();
            w.println("Steps Executed:");
            int step = 1;
            for (String s : result.stepsExecuted()) {
                w.println("  " + step++ + ". " + s);
            }
        }
    }

    private void writeRootCauseHypothesis(Path dir, FailureSignature sig) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("root_cause_hypothesis.txt")))) {
            w.println("=== ROOT CAUSE HYPOTHESIS ===");
            w.println("Timestamp: " + Instant.now());
            w.println("Signature: " + sig.getClass().getSimpleName());
            w.println("Severity : " + sig.severity());
            w.println("Detail   : " + sig.description());
            w.println();

            String hypothesis = switch (sig) {
                case FailureSignature.MemoryLeak m -> String.format("""
                        Heap growing at %.2f%% per sample.
                        Likely causes:
                          - Unbounded cache with no eviction policy
                          - Listener/callback registered but never removed
                          - ThreadLocal not cleaned up after request completion
                          - Large objects retained in static fields
                        Next steps:
                          - Heap dump analysis with MAT or JProfiler
                          - Check top retainers in heap histogram
                          - Look for static collections growing over time
                        """, m.driftRatePerMinute());

                case FailureSignature.ThreadDeadlock d -> String.format("""
                        %d thread(s) deadlocked.
                        Likely causes:
                          - Locks acquired in inconsistent order across code paths
                          - Lock inversion between two monitors
                          - DB row lock held while waiting on application-level lock
                        Next steps:
                          - Inspect lock owner/waiter chains in thread dump
                          - Enforce consistent lock ordering
                          - Consider tryLock() with timeout instead of synchronized
                        """, d.deadlockedCount());

                case FailureSignature.ConnectionPoolExhaustion c -> String.format("""
                        Pool saturated at %d/%d threads.
                        Likely causes:
                          - Slow downstream dependency blocking threads
                          - Connection leak: not returned to pool after use
                          - Pool size too small for current traffic
                        Next steps:
                          - Profile DB query latencies for slow queries > 1s
                          - Audit connection acquisition for try-with-resources
                          - Consider increasing pool size or adding queue depth limit
                        """, c.activeThreads(), c.poolCeiling());

                case FailureSignature.CpuSpike s -> String.format("""
                        CPU sustained at %.1f%% average.
                        Likely causes:
                          - Tight loop without backoff
                          - Expensive serialization in hot path
                          - O(n^2) algorithm triggered by large input
                          - GC pressure from frequent collections
                        Next steps:
                          - CPU profiler (async-profiler or JFR) to find hot methods
                          - Review GC logs for excessive GC activity
                          - Check recent deployments for algorithmic regressions
                        """, s.avgCpuPercent());

                case FailureSignature.ExceptionStorm e -> String.format("""
                        Exception rate at %.1fx baseline (%d exceptions).
                        Likely causes:
                          - Downstream service returning errors (cascading failure)
                          - Bad input reaching unvalidated code after schema change
                          - Race condition producing intermittent NPEs
                          - Expired credential or missing config key
                        Next steps:
                          - Review exception types and stack traces in logs
                          - Check health of downstream services
                          - Correlate with recent deployments or config changes
                        """, e.multiplierAboveBaseline(), e.exceptionCount());
            };

            w.println(hypothesis);
        }
    }

    private void writeIncidentSummary(Path dir, RemediationResult result,
                                      Collection<TelemetrySnapshot> snapshots) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("INCIDENT_SUMMARY.md")))) {
            w.println("# AutoHeal-Mesh Incident Report");
            w.println();
            w.println("**Generated:** " + Instant.now());
            w.println("**Severity:** " + result.triggeredBy().severity());
            w.println("**Status:** AUTO-REMEDIATION FAILED — Human intervention required");
            w.println();
            w.println("## Failure Signature");
            w.println("> " + result.triggeredBy().description());
            w.println();
            w.println("## Auto-Remediation Attempted");
            w.println("| Field | Value |");
            w.println("|-------|-------|");
            w.println("| Playbook | `" + result.playbook() + "` |");
            w.println("| Attempts | " + result.attemptNumber() + " |");
            w.println("| Last Executed | " + result.executedAt() + " |");
            w.println("| Outcome | " + result.actionSummary() + " |");
            w.println();
            w.println("## Diagnostic Files");
            w.println("- `thread_dump.txt`");
            w.println("- `telemetry.csv` — " + snapshots.size() + " snapshots");
            w.println("- `remediation_log.txt`");
            w.println("- `root_cause_hypothesis.txt`");
            w.println();
            w.println("## Next Steps");
            w.println("1. Review `root_cause_hypothesis.txt`");
            w.println("2. Analyze `thread_dump.txt` if deadlock or pool exhaustion is suspected");
            w.println("3. Plot `telemetry.csv` to visualize the anomaly");
            w.println("4. After resolution call `serviceStateMachine.manualReset()`");
        }
    }
}
