package com.finguard.backend.forecasting.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the forecasting method would have performed on this user's own recorded history
 * ({@code evaluatedOn = USER_HISTORY}), by chronological rolling origin. It describes the past; it is not a
 * guarantee, and with a short history it is deliberately marked {@code INCONCLUSIVE}.
 */
public record BacktestSummaryResponse(
        String status,
        String evaluatedOn,
        String method,
        String algorithmVersion,
        String scheme,
        String asOf,
        int usableMonths,
        List<SeriesResult> series,
        List<String> limitations
) {

    /** {@code series} is EXPENSES, INCOME or NET. */
    public record SeriesResult(String series, int observedMonths, List<HorizonResult> horizons) {
    }

    /**
     * @param samples number of forecasts that could be scored at this horizon (each used only data available
     *                at its origin); under 6 the verdict is {@code INCONCLUSIVE}
     * @param verdict MODEL_BETTER, SIMILAR, BASELINE_BETTER (by MAE, with a 10 % margin) or INCONCLUSIVE
     */
    public record HorizonResult(
            int horizon,
            int samples,
            ErrorMetrics model,
            ErrorMetrics naive,
            ErrorMetrics mean3,
            String verdict
    ) {
    }

    /** {@code wape} (sum of absolute errors / sum of actuals, in percent) is null when every actual is zero. */
    public record ErrorMetrics(BigDecimal mae, BigDecimal wapePercent, BigDecimal rmse, BigDecimal bias) {
    }
}
