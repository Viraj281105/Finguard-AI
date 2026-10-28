package com.finguard.backend.audit;

import com.finguard.backend.common.error.ApiError;
import com.finguard.backend.common.error.GlobalExceptionHandler;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.forecasting.dto.ForecastCommon;
import com.finguard.backend.forecasting.service.ForecastingServiceTestAccess;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast regression tests for defects found by the end-to-end audit (no database needed). */
class E2eAuditRegressionUnitTest {

    @Test
    void aDatabaseOutageIsReportedAs503NotAnUnexpectedError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        for (Exception e : new Exception[]{new DataAccessResourceFailureException("down"), new CannotCreateTransactionException("no connection")}) {
            ResponseEntity<ApiError> r = handler.handleDatabaseUnavailable(e);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(r.getBody().message()).contains("temporarily unavailable").doesNotContain("down");
        }
    }

    @Test
    void recordsOlderThanTheForecastWindowGiveUnavailableNotAServerError() {
        // six steady months, all ending 14 months before "today": inside the 36-month history, outside the 12-month window
        CashFlowForecastResponse cash = ForecastingServiceTestAccess.cashFlowForStaleLedger();
        assertThat(cash.reliability()).isEqualTo(ForecastCommon.UNAVAILABLE);
        assertThat(cash.projected()).isEmpty();
        assertThat(cash.warnings().get(0)).contains("fall within the last 12 months").contains("Import recent statements");

        CategorySpendingForecastResponse categories = ForecastingServiceTestAccess.categoriesForStaleLedger();
        assertThat(categories.reliability()).isEqualTo(ForecastCommon.UNAVAILABLE);
        assertThat(categories.categories()).isEmpty();
    }
}
