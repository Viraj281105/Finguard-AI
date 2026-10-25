package com.finguard.backend.forecasting.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Responses for saved scenarios. The snapshot is the result as calculated when the scenario was saved; a re-run
 * recalculates from the current ledger and says whether anything changed.
 */
public final class SavedScenarioResponses {

    private SavedScenarioResponses() {
    }

    /** One row of the saved list. */
    public record Summary(
            String id,
            String name,
            int horizonMonths,
            String methodVersion,
            LocalDateTime createdAt,
            BigDecimal netChange,
            BigDecimal spendingChange
    ) {
    }

    /** The saved adjustments and the result snapshot taken when it was saved. */
    public record Detail(
            Summary summary,
            ScenarioSimulationRequest request,
            ScenarioSimulationResponse snapshot
    ) {
    }

    /**
     * Result of re-running a saved scenario on the current ledger. {@code unchanged} is true when the fresh result is
     * identical to the snapshot (same data, same method version); otherwise the two are returned side by side.
     */
    public record Rerun(
            Summary summary,
            ScenarioSimulationRequest request,
            ScenarioSimulationResponse snapshot,
            ScenarioSimulationResponse current,
            boolean unchanged,
            boolean methodVersionChanged,
            String currentMethodVersion
    ) {
    }
}
