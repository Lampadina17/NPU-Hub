package com.npuhub.service;

import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.InferenceResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide inference telemetry shared by every compatible API surface.
 * Measurements are intentionally allocation-free on the per-token hot path.
 */
public final class InferenceMetrics {
    private static final AtomicInteger ACTIVE_REQUESTS = new AtomicInteger();
    private static final AtomicReference<Measurement> CURRENT = new AtomicReference<>();
    private static final AtomicReference<Snapshot> LAST = new AtomicReference<>(Snapshot.empty());

    private InferenceMetrics() {
    }

    public static Measurement begin(BackendType backend) {
        Measurement measurement = new Measurement(backend);
        ACTIVE_REQUESTS.incrementAndGet();
        CURRENT.set(measurement);
        return measurement;
    }

    public static Map<String, Object> snapshot() {
        Measurement current = CURRENT.get();
        Snapshot snapshot = current != null && !current.finished.get()
                ? current.liveSnapshot()
                : LAST.get();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("generationMetricsAvailable", snapshot.available());
        values.put("generationActive", ACTIVE_REQUESTS.get() > 0);
        values.put("generationActiveRequests", ACTIVE_REQUESTS.get());
        values.put("generationTokensPerSecond", round(snapshot.tokensPerSecond(), 2));
        values.put("generationTimeToFirstTokenMs", round(snapshot.timeToFirstTokenMs(), 1));
        values.put("generationCompletionTokens", snapshot.completionTokens());
        values.put("generationTotalDurationMs", snapshot.totalDurationMs());
        values.put("generationBackend", snapshot.backend());
        values.put("generationSuccessful", snapshot.successful());
        values.put("generationLastCompletedAt", snapshot.completedAtEpochMs());
        return values;
    }

    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }

    public static final class Measurement {
        private final BackendType backend;
        private final long startedNs = System.nanoTime();
        private final AtomicLong firstTokenNs = new AtomicLong();
        private final AtomicInteger completionTokens = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();

        private Measurement(BackendType backend) {
            this.backend = backend;
        }

        public double onToken() {
            long now = System.nanoTime();
            firstTokenNs.compareAndSet(0L, now);
            completionTokens.incrementAndGet();
            return tokensPerSecond(now);
        }

        public void completeStream(boolean successful) {
            long now = System.nanoTime();
            publish(new Snapshot(
                    completionTokens.get() > 0,
                    tokensPerSecond(now),
                    timeToFirstTokenMs(now),
                    completionTokens.get(),
                    nanosToMillis(now - startedNs),
                    backend == null ? "" : backend.name(),
                    successful,
                    System.currentTimeMillis()
            ));
        }

        public void complete(InferenceResponse response) {
            if (response == null) {
                completeStream(false);
                return;
            }
            publish(new Snapshot(
                    true,
                    Math.max(0.0, response.tokensPerSecond()),
                    Math.max(0.0, response.timeToFirstTokenMs()),
                    Math.max(0, response.completionTokens()),
                    Math.max(0L, response.totalExecutionTimeMs()),
                    response.backendUsed() == null ? "" : response.backendUsed().name(),
                    true,
                    System.currentTimeMillis()
            ));
        }

        public void fail() {
            finishWithoutPublishing();
        }

        private Snapshot liveSnapshot() {
            long now = System.nanoTime();
            return new Snapshot(
                    completionTokens.get() > 0,
                    tokensPerSecond(now),
                    timeToFirstTokenMs(now),
                    completionTokens.get(),
                    nanosToMillis(now - startedNs),
                    backend == null ? "" : backend.name(),
                    true,
                    0L
            );
        }

        private double tokensPerSecond(long now) {
            long first = firstTokenNs.get();
            int tokens = completionTokens.get();
            if (first <= 0L || tokens <= 1 || now <= first) {
                return 0.0;
            }
            return tokens * 1_000_000_000.0 / (now - first);
        }

        private double timeToFirstTokenMs(long now) {
            long first = firstTokenNs.get();
            long end = first > 0L ? first : now;
            return (end - startedNs) / 1_000_000.0;
        }

        private void publish(Snapshot snapshot) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            LAST.set(snapshot);
            CURRENT.compareAndSet(this, null);
            ACTIVE_REQUESTS.updateAndGet(value -> Math.max(0, value - 1));
        }

        private void finishWithoutPublishing() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            CURRENT.compareAndSet(this, null);
            ACTIVE_REQUESTS.updateAndGet(value -> Math.max(0, value - 1));
        }

        private long nanosToMillis(long nanos) {
            return Math.max(0L, nanos / 1_000_000L);
        }
    }

    private record Snapshot(
            boolean available,
            double tokensPerSecond,
            double timeToFirstTokenMs,
            int completionTokens,
            long totalDurationMs,
            String backend,
            boolean successful,
            long completedAtEpochMs
    ) {
        private static Snapshot empty() {
            return new Snapshot(false, 0.0, 0.0, 0, 0L, "", false, 0L);
        }
    }
}
