package com.finguard.backend.forecasting.dto;

import java.math.BigDecimal;
import java.util.List;

/** Building blocks shared by every forecasting response. */
public final class ForecastCommon {

    private ForecastCommon() {
    }

    /** Reliability of a forecast: how much evidence it rests on (not how accurate it will be). */
    public static final String RELIABLE = "RELIABLE";
    public static final String LIMITED = "LIMITED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /**
     * A projected amount. {@code lower}/{@code upper} are the 10th and 90th percentile of this method's own past
     * forecast errors on this history added to {@code point}; both are {@code null} when there are too few past
     * errors for a band to be defensible. Never a calibrated probability.
     */
    public record Estimate(BigDecimal point, BigDecimal lower, BigDecimal upper) {
        public static Estimate of(BigDecimal point) {
            return new Estimate(point, null, null);
        }
    }

    /**
     * The history a forecast used.
     *
     * @param monthsUsed       complete calendar months with income or spending that fed the method
     * @param missingMonths    complete months inside the covered range with no usable data (unknown, not zero)
     * @param transactionsUsed transactions in the usable months
     */
    public record HistoryInfo(
            String from,
            String to,
            int monthsUsed,
            int minMonthsLimited,
            int minMonthsReliable,
            List<String> missingMonths,
            int transactionsUsed,
            boolean firstMonthPossiblyPartial
    ) {
    }
}
