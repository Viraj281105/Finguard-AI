package com.finguard.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The only class that talks to the Python AI service.
 *
 * <p>Failure policy: the AI service enriches data but is never required. Any failure (service down,
 * timeout, error status, malformed body) is logged and yields no predictions for the affected batch,
 * so callers degrade gracefully (imported transactions simply stay uncategorised). To keep a dead or
 * hanging service from stalling an import, the client stops after {@value #MAX_CONSECUTIVE_FAILURES}
 * consecutive failed batches and never spends more than {@code ai.service.max-total-duration} on one
 * call; the outcome reports what was not attempted.
 *
 * <p>Every prediction is validated before it leaves this class. Transaction descriptions are never logged.
 */
@Slf4j
@Component
public class AiClient {

    static final String CLASSIFY_PATH = "/v1/classify";
    static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final Set<String> METHODS = Set.of("rule", "model", "none");

    private final RestClient restClient;
    private final int batchSize;
    private final long maxTotalNanos;
    private final LongSupplier nanoClock;

    @Autowired
    public AiClient(@Qualifier("aiRestClient") RestClient restClient, AiProperties properties) {
        this(restClient, properties, System::nanoTime);
    }

    /** Lets tests control the clock that enforces the time budget. */
    AiClient(RestClient restClient, AiProperties properties, LongSupplier nanoClock) {
        this.restClient = restClient;
        this.batchSize = properties.batchSize();
        this.maxTotalNanos = properties.maxTotalDuration().toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * Predicts a category for each item. Items with a null/blank id and repeated ids are ignored.
     */
    public ClassifyOutcome classify(List<ClassifyRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            return ClassifyOutcome.empty();
        }
        List<ClassifyRequest.Item> unique = uniqueItems(items);
        if (unique.isEmpty()) {
            return ClassifyOutcome.empty();
        }

        long start = nanoClock.getAsLong();
        Map<String, AiPrediction> predictions = new HashMap<>();
        int batchesTotal = (unique.size() + batchSize - 1) / batchSize;
        int failed = 0;
        int consecutiveFailures = 0;
        int notAttempted = 0;

        for (int from = 0; from < unique.size(); from += batchSize) {
            List<ClassifyRequest.Item> batch = unique.subList(from, Math.min(from + batchSize, unique.size()));
            boolean outOfTime = nanoClock.getAsLong() - start > maxTotalNanos;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES || outOfTime) {
                notAttempted += batch.size();
                continue;
            }
            Map<String, AiPrediction> answered = classifyBatch(batch);
            if (answered == null) {
                failed++;
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
                predictions.putAll(answered);
            }
        }

        if (notAttempted > 0) {
            log.warn("AI classification gave up on {} of {} items after repeated failures or exhausting its time budget",
                    notAttempted, unique.size());
        }
        log.info("AI answered {}/{} items in {} ms ({} of {} batches failed)", predictions.size(), unique.size(),
                (nanoClock.getAsLong() - start) / 1_000_000, failed, batchesTotal);
        return new ClassifyOutcome(predictions, batchesTotal, failed, notAttempted);
    }

    /** @return validated predictions, or {@code null} when the batch failed as a whole */
    private Map<String, AiPrediction> classifyBatch(List<ClassifyRequest.Item> batch) {
        try {
            ClassifyResponse response = restClient.post()
                    .uri(CLASSIFY_PATH)
                    .body(new ClassifyRequest(List.copyOf(batch)))
                    .retrieve()
                    .body(ClassifyResponse.class);

            if (response == null || response.results() == null) {
                log.warn("AI service returned an empty or malformed classify response");
                return null;
            }
            return validate(batch, response.results());
        } catch (RuntimeException e) {
            // RestClientException (connect/read timeout, 4xx/5xx, unreadable body) or anything the
            // message converters throw: the AI service must never take an import down with it.
            // Only the failure kind is logged: response bodies and parser messages can echo the
            // transaction descriptions we sent.
            log.warn("AI classify call failed; continuing without categories: {}", describe(e));
            return null;
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "I/O error (" + cause.getClass().getSimpleName() + ")";
        }
        return e.getClass().getSimpleName();
    }

    private Map<String, AiPrediction> validate(List<ClassifyRequest.Item> batch, List<AiPrediction> results) {
        Set<String> requested = new HashSet<>();
        batch.forEach(item -> requested.add(item.id()));

        Map<String, AiPrediction> valid = new LinkedHashMap<>();
        int rejected = 0;
        for (AiPrediction p : results) {
            if (!isValid(p, requested) || valid.containsKey(p.id())) {
                rejected++;
                continue;
            }
            valid.put(p.id(), p);
        }
        if (rejected > 0) {
            log.warn("Ignored {} invalid or unexpected AI predictions", rejected);
        }
        return valid;
    }

    private static boolean isValid(AiPrediction p, Set<String> requestedIds) {
        return p != null
                && p.id() != null && requestedIds.contains(p.id())
                && p.category() != null && !p.category().isBlank()
                && Double.isFinite(p.confidence()) && p.confidence() >= 0.0 && p.confidence() <= 1.0
                && p.method() != null && METHODS.contains(p.method());
    }

    private static List<ClassifyRequest.Item> uniqueItems(List<ClassifyRequest.Item> items) {
        Set<String> seen = new HashSet<>();
        return items.stream()
                .filter(i -> i != null && i.id() != null && !i.id().isBlank() && seen.add(i.id()))
                .toList();
    }
}
