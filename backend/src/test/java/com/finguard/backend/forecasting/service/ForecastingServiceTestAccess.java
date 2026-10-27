package com.finguard.backend.forecasting.service;

import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.ledger.transactions.Transaction;

import java.time.YearMonth;
import java.util.List;

import static com.finguard.backend.forecasting.service.LedgerFixtures.d;
import static com.finguard.backend.forecasting.service.LedgerFixtures.history;
import static com.finguard.backend.forecasting.service.LedgerFixtures.months;
import static com.finguard.backend.forecasting.service.LedgerFixtures.service;

/**
 * Test-only bridge into the package-private forecasting entry points, so tests in other packages can exercise them.
 * SYNTHETIC ledger: Jan-Jun 2025 with the clock at 2026-10-27 (the latest month is 16 months old).
 */
public final class ForecastingServiceTestAccess {

    private ForecastingServiceTestAccess() {
    }

    private static ForecastHistory staleHistory() {
        List<Transaction> tx = months(YearMonth.of(2025, 1), 6, "50000", "20000", "10000");
        return history(tx, d(2026, 10, 27));
    }

    public static CashFlowForecastResponse cashFlowForStaleLedger() {
        return service().cashFlow(staleHistory(), 3);
    }

    public static CategorySpendingForecastResponse categoriesForStaleLedger() {
        return service().categories(staleHistory(), 3);
    }
}
