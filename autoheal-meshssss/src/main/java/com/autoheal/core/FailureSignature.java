package com.autoheal.core;

public sealed interface FailureSignature
        permits FailureSignature.MemoryLeak,
                FailureSignature.ThreadDeadlock,
                FailureSignature.ConnectionPoolExhaustion,
                FailureSignature.CpuSpike,
                FailureSignature.ExceptionStorm {

    enum Severity { WARN, ERROR, CRITICAL }

    Severity severity();
    String description();

    record MemoryLeak(double driftRatePerMinute, double currentHeapPct, Severity severity)
            implements FailureSignature {
        @Override
        public String description() {
            return String.format("Memory leak detected — heap at %.1f%%, growing %.2f%%/min",
                    currentHeapPct, driftRatePerMinute);
        }
    }

    record ThreadDeadlock(int deadlockedCount, long[] threadIds, Severity severity)
            implements FailureSignature {
        @Override
        public String description() {
            return String.format("Thread deadlock — %d threads stuck (IDs: %s)",
                    deadlockedCount, java.util.Arrays.toString(threadIds));
        }
    }

    record ConnectionPoolExhaustion(int activeThreads, int poolCeiling, Severity severity)
            implements FailureSignature {
        @Override
        public String description() {
            return String.format("Connection pool exhausted — %d/%d threads active",
                    activeThreads, poolCeiling);
        }
    }

    record CpuSpike(double avgCpuPercent, Severity severity)
            implements FailureSignature {
        @Override
        public String description() {
            return String.format("CPU spike — %.1f%% average over sliding window", avgCpuPercent);
        }
    }

    record ExceptionStorm(long exceptionCount, double multiplierAboveBaseline, Severity severity)
            implements FailureSignature {
        @Override
        public String description() {
            return String.format("Exception storm — %d exceptions (%.1fx above baseline)",
                    exceptionCount, multiplierAboveBaseline);
        }
    }
}
