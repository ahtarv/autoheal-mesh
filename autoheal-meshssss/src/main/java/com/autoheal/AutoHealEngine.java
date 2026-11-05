package com.autoheal;

import com.autoheal.agent.TelemetryCollector;
import com.autoheal.audit.AuditVerificationLoop;
import com.autoheal.core.*;
import com.autoheal.detector.AnomalyDetector;
import com.autoheal.escalation.EscalationBundler;
import com.autoheal.orchestrator.PlaybookOrchestrator;

import java.nio.file.Path;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

public final class AutoHealEngine {

    private static final Logger LOG = Logger.getLogger(AutoHealEngine.class.getName());

    private final TelemetryCollector collector;
    private final AnomalyDetector detector;
    private final PlaybookOrchestrator orchestrator;
    private final AuditVerificationLoop auditLoop;
    private final EscalationBundler escalationBundler;
    private final ServiceStateMachine stateMachine;

    private final EventBus<TelemetrySnapshot> telemetryBus;
    private final EventBus<List<FailureSignature>> anomalyBus;

    private final Deque<TelemetrySnapshot> snapshotHistory = new ConcurrentLinkedDeque<>();
    private static final int HISTORY_CAPACITY = 300;

    private final Config config;

    public AutoHealEngine(Config config) {
        this.config = config;
        this.stateMachine = new ServiceStateMachine();

        this.telemetryBus = new EventBus<>("telemetry", 512);
        this.anomalyBus   = new EventBus<>("anomaly", 64);

        this.collector = new TelemetryCollector(telemetryBus, config.pollIntervalMs());

        this.detector = new AnomalyDetector(
                anomalyBus,
                config.slidingWindowSize(),
                config.memLeakSlopeThreshold(),
                config.cpuSpikeThreshold(),
                config.poolCeiling(),
                config.exceptionBaselineMultiplier()
        );

        this.orchestrator = new PlaybookOrchestrator();

        this.auditLoop = new AuditVerificationLoop(
                config.verificationWindowMs(),
                config.auditCheckIntervalMs(),
                this::onVerificationFailed
        );

        this.escalationBundler = new EscalationBundler(config.bundleOutputDir());
    }

    public void start() {
        telemetryBus.subscribe(this::onTelemetrySnapshot);
        telemetryBus.start();

        anomalyBus.subscribe(this::onAnomaliesDetected);
        anomalyBus.start();

        collector.start();
        LOG.info("[AutoHealEngine] Started.");
    }

    public void stop() {
        collector.stop();
        telemetryBus.stop();
        anomalyBus.stop();
        LOG.info("[AutoHealEngine] Stopped.");
    }

    private void onTelemetrySnapshot(TelemetrySnapshot snap) {
        snapshotHistory.addLast(snap);
        while (snapshotHistory.size() > HISTORY_CAPACITY) snapshotHistory.pollFirst();

        detector.process(snap);
        auditLoop.ingestSnapshot(snap);
    }

    private void onAnomaliesDetected(List<FailureSignature> signatures) {
        stateMachine.onAnomalyDetected();

        for (FailureSignature sig : signatures) {
            LOG.warning("[Engine] Anomaly: " + sig.description());

            stateMachine.onRemediationStarted();
            auditLoop.captureBaseline();

            RemediationResult result = orchestrator.execute(sig, stateMachine.getRemediationAttempts());
            LOG.info("[Engine] Remediation result: " + result);

            if (result.success()) {
                auditLoop.verifyAsync(result);
                stateMachine.onRemediationSucceeded();
            } else {
                boolean shouldEscalate = stateMachine.onRemediationFailed();
                if (shouldEscalate) {
                    triggerEscalation(result);
                }
            }
        }
    }

    private void onVerificationFailed(RemediationResult result) {
        LOG.severe("[Engine] Post-remediation verification failed for " + result.playbook());
        boolean escalate = stateMachine.onRemediationFailed();
        if (escalate) {
            triggerEscalation(result);
        }
    }

    private void triggerEscalation(RemediationResult result) {
        LOG.severe("[Engine] Escalating to human operator");
        Path bundle = escalationBundler.generateBundle(result, snapshotHistory);
        LOG.severe("[Engine] Bundle written to: " + bundle.toAbsolutePath());
    }

    public TelemetryCollector getCollector() { return collector; }
    public ServiceStateMachine getStateMachine() { return stateMachine; }

    public record Config(
            long pollIntervalMs,
            int  slidingWindowSize,
            double memLeakSlopeThreshold,
            double cpuSpikeThreshold,
            int  poolCeiling,
            double exceptionBaselineMultiplier,
            long verificationWindowMs,
            long auditCheckIntervalMs,
            String bundleOutputDir
    ) {
        public static Config defaults() {
            return new Config(1_000, 30, 0.5, 80.0, 200, 3.0, 300_000, 5_000, "logs/bundles");
        }

        public static Config fastDemo() {
            return new Config(500, 10, 0.3, 70.0, 50, 2.0, 30_000, 2_000, "logs/bundles");
        }
    }
}
