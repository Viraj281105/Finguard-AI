package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;

public record DataQualitySummaryDto(
        long totalTransactions,
        long uncategorizedCount,
        BigDecimal uncategorizedPercentage,
        boolean hasSufficientHistory
) {
}
