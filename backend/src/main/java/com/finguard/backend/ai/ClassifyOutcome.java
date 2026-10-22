package com.finguard.backend.ai;

import java.util.Map;

/**
 * What a {@link AiClient#classify} call achieved.
 *
 * @param predictions       validated predictions by item id (abstentions included)
 * @param batchesTotal      number of batches the input was split into
 * @param batchesFailed     batches that failed (timeout, connection, error status, malformed body)
 * @param itemsNotAttempted items never sent because the client gave up (repeated failures or time budget)
 */
public record ClassifyOutcome(
        Map<String, AiPrediction> predictions,
        int batchesTotal,
        int batchesFailed,
        int itemsNotAttempted
) {

    public static ClassifyOutcome empty() {
        return new ClassifyOutcome(Map.of(), 0, 0, 0);
    }

    /** True when every batch was answered. */
    public boolean fullyAnswered() {
        return batchesFailed == 0 && itemsNotAttempted == 0;
    }

    /** True when there was something to ask but nothing came back. */
    public boolean unavailable() {
        return batchesTotal > 0 && predictions.isEmpty() && (batchesFailed > 0 || itemsNotAttempted > 0);
    }
}
