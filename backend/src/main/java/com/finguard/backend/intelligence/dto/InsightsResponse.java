package com.finguard.backend.intelligence.dto;

import java.util.List;

public record InsightsResponse(
        PeriodDto period,
        List<FinancialInsightDto> insights,
        List<UnusualTransactionDto> unusualTransactions
) {
}
