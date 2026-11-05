package com.autoheal;

import com.autoheal.core.FailureSignature;
import com.autoheal.core.ServiceStateMachine;
import com.autoheal.core.TelemetrySnapshot;
import com.autoheal.detector.AnomalyDetector;
import com.autoheal.detector.SlidingWindow;
import com.autoheal.core.EventBus;
import com.autoheal.orchestrator.CircuitBreaker;
import com.autoheal.orchestrator.PlaybookOrchestrator;
import com.autoheal.core.RemediationResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class AutoHealMeshTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws InterruptedException {
        testSlidingWindowAverage();
        testSlidingWindowSlope_upwardTrend();
        testSlidingWindowSlope_flat();
        testSlidingWindowCapacityEviction();

        testFailureSignatureDescriptions();
        testSealedHierarchyPatternMatch();

        testTelemetrySnapshotHeapPctComputation();
        testTelemetrySnapshotValidation();

        testStateMachineHappyPath();
        testStateMachineEscalationAfterTwoFailures();
        testStateMachineManualReset();

        testAnomalyDetectorMemoryLeak();
        testAnomalyDetectorDeadlockImmediate();
        testAnomalyDetectorNoFalsePositiveOnFlatHeap();
        testAnomalyDetectorPoolExhaustion();

        testCircuitBreakerTripAndCooldown();
        testCircuitBreakerRecordFailuresAutoTrip();

        testOrchestratorExecutesCorrectPlaybook();

        System.out.println("\n------------------------------------------");
        System.out.printf("  Results: %d passed, %d failed%n", passed, failed);
        System.out.println("------------------------------------------");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void testSlidingWindowAverage() {
        SlidingWindow w = new SlidingWindow(5);
        w.add(10); w.add(20); w.add(30);
        assertEquals("SlidingWindow.average", 20.0, w.average().orElse(-1), 0.001);
    }

    static void testSlidingWindowSlope_upwardTrend() {
        SlidingWindow w = new SlidingWindow(5);
        for (int i = 1; i <= 5; i++) w.add(i * 10.0);
        assertTrue("SlidingWindow.slope positive for upward trend", w.slope() > 5.0);
    }

    static void testSlidingWindowSlope_flat() {
        SlidingWindow w = new SlidingWindow(5);
        for (int i = 0; i < 5; i++) w.add(50.0);
        assertEquals("SlidingWindow.slope zero for flat data", 0.0, w.slope(), 0.001);
    }

    static void testSlidingWindowCapacityEviction() {
        SlidingWindow w = new SlidingWindow(3);
        w.add(100); w.add(200); w.add(300); w.add(400);
        assertEquals("SlidingWindow capacity", 3, w.size());
        assertTrue("SlidingWindow evicts oldest", w.average().orElse(-1) > 200);
    }

    static void testFailureSignatureDescriptions() {
        FailureSignature leak = new FailureSignature.MemoryLeak(2.5, 78.3, FailureSignature.Severity.ERROR);
        assertTrue("MemoryLeak description contains heap%", leak.description().contains("78.3"));

        FailureSignature deadlock = new FailureSignature.ThreadDeadlock(3, new long[]{1, 2, 3},
                FailureSignature.Severity.CRITICAL);
        assertTrue("ThreadDeadlock description contains count", deadlock.description().contains("3"));
    }

    static void testSealedHierarchyPatternMatch() {
        FailureSignature sig = new FailureSignature.CpuSpike(92.0, FailureSignature.Severity.CRITICAL);
        String result = switch (sig) {
            case FailureSignature.CpuSpike s               -> "cpu-" + (int) s.avgCpuPercent();
            case FailureSignature.MemoryLeak m             -> "mem";
            case FailureSignature.ThreadDeadlock d         -> "deadlock";
            case FailureSignature.ConnectionPoolExhaustion c -> "pool";
            case FailureSignature.ExceptionStorm e         -> "storm";
        };
        assertEquals("Sealed switch pattern match", "cpu-92", result);
    }

    static void testTelemetrySnapshotHeapPctComputation() {
        TelemetrySnapshot snap = TelemetrySnapshot.of(
                Instant.now(), 50.0, 512 * 1024 * 1024L, 1024 * 1024 * 1024L, 10, 0, 0, 0);
        assertEquals("HeapPct = 50%", 50.0, snap.heapUsagePercent(), 0.1);
    }

    static void testTelemetrySnapshotValidation() {
        boolean threw = false;
        try {
            new TelemetrySnapshot(Instant.now(), 150.0, 0, 1, 1, 0, 0, 0, 0.0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("TelemetrySnapshot rejects CPU > 100", threw);
    }

    static void testStateMachineHappyPath() {
        ServiceStateMachine sm = new ServiceStateMachine();
        assertEquals("Initial state HEALTHY", ServiceStateMachine.State.HEALTHY, sm.getState());
        sm.onAnomalyDetected();
        assertEquals("After anomaly: DEGRADED", ServiceStateMachine.State.DEGRADED, sm.getState());
        sm.onRemediationStarted();
        assertEquals("After started: REMEDIATING", ServiceStateMachine.State.REMEDIATING, sm.getState());
        sm.onRemediationSucceeded();
        assertEquals("After success: HEALTHY", ServiceStateMachine.State.HEALTHY, sm.getState());
    }

    static void testStateMachineEscalationAfterTwoFailures() {
        ServiceStateMachine sm = new ServiceStateMachine();
        sm.onAnomalyDetected();
        sm.onRemediationStarted();
        assertFalse("First failure should not escalate", sm.onRemediationFailed());
        sm.onRemediationStarted();
        assertTrue("Second failure should escalate", sm.onRemediationFailed());
        assertEquals("State after escalation: ESCALATED", ServiceStateMachine.State.ESCALATED, sm.getState());
    }

    static void testStateMachineManualReset() {
        ServiceStateMachine sm = new ServiceStateMachine();
        sm.onAnomalyDetected();
        sm.onRemediationStarted();
        sm.onRemediationFailed();
        sm.onRemediationStarted();
        sm.onRemediationFailed();
        sm.manualReset();
        assertEquals("After manualReset: HEALTHY", ServiceStateMachine.State.HEALTHY, sm.getState());
        assertEquals("Attempt counter reset", 0, sm.getRemediationAttempts());
    }

    static void testAnomalyDetectorMemoryLeak() throws InterruptedException {
        AtomicReference<List<FailureSignature>> captured = new AtomicReference<>(List.of());
        EventBus<List<FailureSignature>> bus = new EventBus<>("test-anomaly", 16);
        bus.subscribe(captured::set);
        bus.start();

        AnomalyDetector detector = new AnomalyDetector(bus, 10, 0.5, 90.0, 500, 3.0);

        for (int i = 0; i < 10; i++) {
            TelemetrySnapshot snap = TelemetrySnapshot.of(
                    Instant.now(), 10.0,
                    (long) ((50 + i * 2) * 1024 * 1024),
                    1024L * 1024 * 1024,
                    10, 0, 0, 0
            );
            detector.process(snap);
            Thread.sleep(50);
        }

        Thread.sleep(200);
        bus.stop();

        assertTrue("AnomalyDetector detects memory leak",
                captured.get().stream().anyMatch(s -> s instanceof FailureSignature.MemoryLeak));
    }

    static void testAnomalyDetectorDeadlockImmediate() throws InterruptedException {
        AtomicReference<List<FailureSignature>> captured = new AtomicReference<>(List.of());
        EventBus<List<FailureSignature>> bus = new EventBus<>("test-deadlock", 16);
        bus.subscribe(captured::set);
        bus.start();

        AnomalyDetector detector = new AnomalyDetector(bus, 10, 0.5, 90.0, 500, 3.0);

        detector.process(TelemetrySnapshot.of(
                Instant.now(), 10.0, 100L * 1024 * 1024, 1024L * 1024 * 1024,
                10, 2, 0, 0
        ));

        Thread.sleep(200);
        bus.stop();

        assertTrue("AnomalyDetector detects deadlock immediately",
                captured.get().stream().anyMatch(s -> s instanceof FailureSignature.ThreadDeadlock));
    }

    static void testAnomalyDetectorNoFalsePositiveOnFlatHeap() throws InterruptedException {
        AtomicReference<List<FailureSignature>> captured = new AtomicReference<>(null);
        EventBus<List<FailureSignature>> bus = new EventBus<>("test-flat", 16);
        bus.subscribe(captured::set);
        bus.start();

        AnomalyDetector detector = new AnomalyDetector(bus, 10, 0.5, 90.0, 500, 3.0);

        for (int i = 0; i < 10; i++) {
            detector.process(TelemetrySnapshot.of(
                    Instant.now(), 10.0, 512L * 1024 * 1024, 1024L * 1024 * 1024,
                    10, 0, 0, 0
            ));
        }

        Thread.sleep(100);
        bus.stop();

        List<FailureSignature> sigs = captured.get();
        assertTrue("No false positive for flat heap",
                sigs == null || sigs.stream().noneMatch(s -> s instanceof FailureSignature.MemoryLeak));
    }

    static void testAnomalyDetectorPoolExhaustion() throws InterruptedException {
        AtomicReference<List<FailureSignature>> captured = new AtomicReference<>(List.of());
        EventBus<List<FailureSignature>> bus = new EventBus<>("test-pool", 16);
        bus.subscribe(captured::set);
        bus.start();

        AnomalyDetector detector = new AnomalyDetector(bus, 10, 0.5, 90.0, 50, 3.0);

        detector.process(TelemetrySnapshot.of(
                Instant.now(), 10.0, 100L * 1024 * 1024, 1024L * 1024 * 1024,
                55, 0, 0, 0
        ));

        Thread.sleep(200);
        bus.stop();

        assertTrue("AnomalyDetector detects pool exhaustion",
                captured.get().stream().anyMatch(s -> s instanceof FailureSignature.ConnectionPoolExhaustion));
    }

    static void testCircuitBreakerTripAndCooldown() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test-cb", 3, 100);
        assertFalse("Initially closed", cb.isOpen());
        cb.trip();
        assertTrue("Open after trip", cb.isOpen());
        Thread.sleep(150);
        assertFalse("Half-open after cooldown", cb.isOpen());
    }

    static void testCircuitBreakerRecordFailuresAutoTrip() {
        CircuitBreaker cb = new CircuitBreaker("test-auto", 3, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse("Not open after 2 failures", cb.isOpen());
        cb.recordFailure();
        assertTrue("Open after 3 failures", cb.isOpen());
    }

    static void testOrchestratorExecutesCorrectPlaybook() {
        PlaybookOrchestrator orch = new PlaybookOrchestrator();

        RemediationResult r1 = orch.execute(
                new FailureSignature.ThreadDeadlock(1, new long[]{42L}, FailureSignature.Severity.CRITICAL), 1);
        assertEquals("Deadlock -> THREAD_DUMP_AND_RESTART",
                RemediationResult.PlaybookType.THREAD_DUMP_AND_RESTART, r1.playbook());
        assertTrue("Deadlock playbook succeeds", r1.success());
        assertFalse("Steps not empty", r1.stepsExecuted().isEmpty());

        RemediationResult r2 = orch.execute(
                new FailureSignature.ConnectionPoolExhaustion(60, 50, FailureSignature.Severity.ERROR), 1);
        assertEquals("Pool exhaustion -> CONNECTION_POOL_FLUSH",
                RemediationResult.PlaybookType.CONNECTION_POOL_FLUSH, r2.playbook());
    }

    static void assertEquals(String label, Object expected, Object actual) {
        if (expected.equals(actual)) pass(label);
        else fail(label, "expected=" + expected + " actual=" + actual);
    }

    static void assertEquals(String label, double expected, double actual, double delta) {
        if (Math.abs(expected - actual) <= delta) pass(label);
        else fail(label, "expected=" + expected + " actual=" + actual);
    }

    static void assertEquals(String label, int expected, int actual) {
        if (expected == actual) pass(label);
        else fail(label, "expected=" + expected + " actual=" + actual);
    }

    static void assertTrue(String label, boolean condition) {
        if (condition) pass(label); else fail(label, "condition was false");
    }

    static void assertFalse(String label, boolean condition) {
        if (!condition) pass(label); else fail(label, "condition was true");
    }

    static void pass(String label) {
        System.out.printf("  \u001B[32mPASS\u001B[0m  %s%n", label);
        passed++;
    }

    static void fail(String label, String reason) {
        System.out.printf("  \u001B[31mFAIL\u001B[0m  %s — %s%n", label, reason);
        failed++;
    }
}
