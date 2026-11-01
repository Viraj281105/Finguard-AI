package com.finguard.backend.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralised domain SLI metrics for FinGuard-AI reliability objectives (SLO-1 through SLO-6).
 *
 * <p>All metric tags are strictly bounded cardinality (status, bank, outcome) and never carry
 * user IDs, emails, or transaction narration strings.
 */
@Component
public class FinGuardMetrics {

    private final MeterRegistry registry;
    private final AtomicLong reconcilerDeltaPaise = new AtomicLong(0L);
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong(0L);

    public FinGuardMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("finguard.ledger.reconciler.delta.paise", reconcilerDeltaPaise, AtomicLong::get)
                .description("Absolute ledger reconciliation drift in paise (SLO-4 invariant: must equal 0)")
                .register(registry);
        Gauge.builder("finguard.outbox.oldest.pending.seconds", outboxOldestPendingSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest unpublished outbox event (SLO-5 freshness)")
                .register(registry);
    }

    public void recordImportCompleted(String status, Duration duration, int rowsImported, int duplicates, int rejected) {
        Counter.builder("finguard.import.jobs")
                .description("Total statement import jobs completed by terminal status")
                .tag("status", status)
                .register(registry)
                .increment();

        Timer.builder("finguard.import.duration")
                .description("End-to-end statement import execution time (SLO-3)")
                .tag("status", status)
                .register(registry)
                .record(duration);

        if (rowsImported > 0) {
            Counter.builder("finguard.import.rows")
                    .description("Statement rows processed by outcome")
                    .tag("outcome", "imported")
                    .register(registry)
                    .increment(rowsImported);
        }
        if (duplicates > 0) {
            Counter.builder("finguard.import.rows")
                    .description("Statement rows processed by outcome")
                    .tag("outcome", "duplicate")
                    .register(registry)
                    .increment(duplicates);
        }
        if (rejected > 0) {
            Counter.builder("finguard.import.rows")
                    .description("Statement rows processed by outcome")
                    .tag("outcome", "rejected")
                    .register(registry)
                    .increment(rejected);
        }
    }

    public void recordAiBatch(String outcome, Duration duration, int itemsCount) {
        Counter.builder("finguard.ai.classify.batches")
                .description("Outbound AI classification batch calls by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();

        Timer.builder("finguard.ai.classify.duration")
                .description("Outbound AI classification batch latency")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);

        if (itemsCount > 0) {
            Counter.builder("finguard.ai.classify.items")
                    .description("Items sent to AI classification service")
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment(itemsCount);
        }
    }

    public void setReconcilerDeltaPaise(long deltaPaise) {
        reconcilerDeltaPaise.set(Math.abs(deltaPaise));
    }

    public void setOutboxOldestPendingSeconds(long seconds) {
        outboxOldestPendingSeconds.set(Math.max(0L, seconds));
    }
}

