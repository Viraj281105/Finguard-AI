package com.finguard.backend.privacy.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AccountExportResponse(
        String exportedAt,
        UserProfileExportDto profile,
        ExportRecordCountsDto recordCounts,
        List<ExportTransactionDto> transactions,
        List<ExportInvestmentDto> investments,
        List<ExportLoanDto> loans,
        List<ExportUploadJobDto> uploadJobs,
        List<ExportBudgetDto> budgets,
        List<ExportGoalDto> goals,
        List<ExportGoalContributionDto> goalContributions,
        List<ExportSavedScenarioDto> savedScenarios
) {
    public record UserProfileExportDto(
            String id,
            String name,
            String email,
            LocalDateTime createdAt,
            String deletionRequestedAt,
            String deletionScheduledAt
    ) {
    }

    public record ExportRecordCountsDto(
            int transactions,
            int investments,
            int loans,
            int uploadJobs,
            int budgets,
            int goals,
            int goalContributions,
            int savedScenarios,
            int totalRecords
    ) {
    }

    public record ExportTransactionDto(
            String id,
            String title,
            BigDecimal amount,
            String type,
            LocalDate date,
            String category,
            String categorySource,
            BigDecimal categoryConfidence,
            boolean categoryNeedsReview,
            String note,
            String source,
            String importJobId,
            LocalDateTime createdAt
    ) {
    }

    public record ExportInvestmentDto(
            String id,
            String name,
            String type,
            BigDecimal amountInvested,
            BigDecimal currentValue,
            String notes,
            LocalDateTime createdAt
    ) {
    }

    public record ExportLoanDto(
            String id,
            String name,
            String type,
            BigDecimal principal,
            BigDecimal emiAmount,
            BigDecimal interestRate,
            LocalDate startDate,
            LocalDate endDate,
            boolean active,
            LocalDateTime createdAt
    ) {
    }

    public record ExportUploadJobDto(
            String id,
            String originalFilename,
            String bankName,
            String status,
            int totalRows,
            int rowsProcessed,
            int duplicateRows,
            int rejectedRows,
            int skippedRows,
            BigDecimal closingBalance,
            LocalDate statementFrom,
            LocalDate statementTo,
            String classificationStatus,
            int classifiedRows,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    public record ExportBudgetDto(
            String id,
            String yearMonth,
            String category,
            BigDecimal amount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ExportGoalDto(
            String id,
            String name,
            String description,
            BigDecimal targetAmount,
            BigDecimal currentAmount,
            LocalDate targetDate,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ExportGoalContributionDto(
            String id,
            String goalId,
            BigDecimal amount,
            LocalDate contributionDate,
            String note,
            LocalDateTime createdAt
    ) {
    }

    public record ExportSavedScenarioDto(
            String id,
            String name,
            int horizonMonths,
            String methodVersion,
            String requestJson,
            String resultJson,
            LocalDateTime createdAt
    ) {
    }
}

