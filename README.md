# autoheal-mesh

![Java](https://img.shields.io/badge/Java-23-orange?style=flat-square&logo=openjdk)
![Build](https://img.shields.io/badge/Build-passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)
![Platform](https://img.shields.io/badge/Platform-JVM-red?style=flat-square)
![Concurrency](https://img.shields.io/badge/Concurrency-Virtual_Threads-purple?style=flat-square)
![Status](https://img.shields.io/badge/Status-Active-success?style=flat-square)

> Autonomous failure detection and self-remediation engine for distributed JVM-based backend services.

---

## Overview

`autoheal-mesh` is a zero-dependency, embedded autonomous remediation engine that monitors JVM health in real time, classifies failure signatures, executes remediation playbooks, and escalates to human operators when automated recovery fails.

It runs entirely inside the JVM — no external agents, no message brokers, no sidecar processes. Everything from telemetry collection to escalation bundle generation happens in-process using standard Java APIs.

---

## Architecture

```
TelemetryCollector
      |
      |  publishes TelemetrySnapshot
      v
  EventBus<TelemetrySnapshot>
      |
      |  dispatches to
      v
  AnomalyDetector ---------> EventBus<List<FailureSignature>>
  (sliding window)                       |
                                         |
                                         v
                              PlaybookOrchestrator
                              (sealed-class switch)
                                         |
                                         v
                              AuditVerificationLoop
                              (post-remediation watchdog)
                                         |
                                    (on failure x2)
                                         |
                                         v
                              EscalationBundler
                              (writes diagnostic bundle to disk)
```

---

## Modules

| Module | Package | Responsibility |
|---|---|---|
| **agent** | `com.autoheal.agent` | Embedded telemetry daemon using JMX APIs |
| **detector** | `com.autoheal.detector` | Sliding-window anomaly detection engine |
| **orchestrator** | `com.autoheal.orchestrator` | Playbook execution engine + circuit breaker |
| **audit** | `com.autoheal.audit` | Post-remediation verification watchdog |
| **escalation** | `com.autoheal.escalation` | Diagnostic bundle generator |
| **core** | `com.autoheal.core` | Shared models, state machine, event bus |

---

## Failure Signatures Detected

- **Memory Leak** — heap usage slope exceeds threshold across sliding window (linear regression)
- **Thread Deadlock** — JVM `ThreadMXBean` reports deadlocked threads
- **Connection Pool Exhaustion** — active thread count saturates pool ceiling
- **CPU Spike** — sustained CPU average exceeds threshold over window
- **Exception Storm** — unhandled exception rate exceeds baseline by a configured multiplier

---

## Remediation Playbooks

| Failure | Playbook |
|---|---|
| Thread Deadlock | Thread dump + graceful worker restart |
| Connection Pool Exhaustion | Flush idle connections + trip circuit breaker |
| Memory Leak | Force GC, rolling restart if heap critical |
| CPU Spike | Pause background jobs, reduce thread pool |
| Exception Storm | Rate limiting + dead-letter queue routing |

---

## State Machine

```
HEALTHY
  |
  | anomaly detected
  v
DEGRADED
  |
  | playbook triggered
  v
REMEDIATING
  |          |
  |          | fix verified
  |          v
  |        HEALTHY
  |
  | fix failed x2
  v
ESCALATED  <-- human operator required
  |
  | manual reset
  v
HEALTHY
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 23 with `--enable-preview` |
| Concurrency | Virtual threads (`Thread.ofVirtual()`), `ScheduledExecutorService` |
| State Machine | Hand-rolled FSM |
| Anomaly Detection | Custom circular-buffer sliding window with least-squares regression |
| Metrics | `MemoryMXBean`, `ThreadMXBean`, `OperatingSystemMXBean` |
| Circuit Breaker | Custom CLOSED / OPEN / HALF_OPEN implementation |
| Event Bus | Bounded `BlockingQueue` with virtual thread dispatch |
| Build | `build.bat` (javac + jar, no Maven/Gradle) |
| Tests | Self-contained test runner, no external framework |

---

## Java 23 Features Used

- **Virtual threads** — telemetry collection, event bus dispatch, and audit verification all run on virtual threads with zero OS thread overhead
- **Records** — `TelemetrySnapshot`, `RemediationResult`, `AutoHealEngine.Config`
- **Sealed interfaces** — `FailureSignature` sealed hierarchy with five permitted record implementations
- **Pattern matching switch** — `PlaybookOrchestrator` dispatches playbooks via exhaustive sealed-class switch expressions

---

## Getting Started

**Requirements:** JDK 23 installed at `C:\Program Files\Java\jdk-23`

**Build and run tests:**
```bat
build.bat
```

**Run the demo:**
```bat
run.bat
```

The demo starts the engine, injects an exception storm after 5 seconds, then allocates heap pressure to trigger the memory leak detector. Watch the console for state machine transitions, playbook executions, and audit verifications.

---

## Escalation Bundles

When auto-remediation fails twice, the engine writes a diagnostic bundle to `logs/bundles/`:

```
logs/bundles/incident_<timestamp>/
  INCIDENT_SUMMARY.md
  thread_dump.txt
  telemetry.csv
  remediation_log.txt
  root_cause_hypothesis.txt
```

The `root_cause_hypothesis.txt` contains pattern-matched analysis based on the detected failure signature with specific investigation leads.

---

## Configuration

Two built-in configs are available via `AutoHealEngine.Config`:

| Parameter | `defaults()` | `fastDemo()` |
|---|---|---|
| Poll interval | 1000ms | 500ms |
| Sliding window | 30 samples | 10 samples |
| Heap slope threshold | 0.5%/sample | 0.3%/sample |
| CPU spike threshold | 80% | 70% |
| Pool ceiling | 200 threads | 50 threads |
| Exception multiplier | 3x baseline | 2x baseline |
| Verification window | 300s | 30s |

Custom configs can be constructed directly via the `Config` record.

---

## Project Structure

```
autoheal-mesh/
  src/
    main/java/com/autoheal/
      agent/          TelemetryCollector.java
      audit/          AuditVerificationLoop.java
      core/           EventBus.java
                      FailureSignature.java
                      RemediationResult.java
                      ServiceStateMachine.java
                      TelemetrySnapshot.java
      detector/       AnomalyDetector.java
                      SlidingWindow.java
      escalation/     EscalationBundler.java
      orchestrator/   CircuitBreaker.java
                      PlaybookOrchestrator.java
      AutoHealEngine.java
      Main.java
    test/java/com/autoheal/
      AutoHealMeshTest.java
  logs/
    bundles/
  out/
  build.bat
  run.bat
```

---

## License

MIT
