package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DataQualityResponse(
        LocalDate coverageFrom,
        LocalDate coverageTo,
        long totalDaysCovered,
        int totalMonthsCovered,
        long totalTransactions,
        long uncategorizedCount,
        BigDecimal uncategorizedPercentage,
        List<String> missingMonths,
        boolean hasSufficientHistory,
        List<String> caveats
) {
}
