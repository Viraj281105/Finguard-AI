package com.finguard.backend.ingestion.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.backend.categorization.ClassificationStatus;
import com.finguard.backend.ingestion.parser.RowError;
import com.finguard.backend.ingestion.service.UploadJob;
import com.finguard.backend.ingestion.service.UploadJob.JobStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** JSON view of an {@link UploadJob}; never exposes the owning user. */
public record UploadStatusResponse(
        String jobId,
        JobStatus status,
        String bankName,
        String originalFilename,
        Integer totalRows,
        Integer rowsProcessed,
        Integer duplicateRows,
        Integer rejectedRows,
        Integer skippedRows,
        List<RowErrorDto> rowErrors,
        ClassificationStatus classificationStatus,
        Integer classifiedRows,
        BigDecimal closingBalance,
        LocalDate statementFrom,
        LocalDate statementTo,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static UploadStatusResponse from(UploadJob job) {
        List<RowErrorDto> errors = parseErrors(job.getRowErrorsJson());
        return new UploadStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getBankName(),
                job.getOriginalFilename(),
                job.getTotalRows(),
                job.getRowsProcessed(),
                job.getDuplicateRows(),
                job.getRejectedRows(),
                job.getSkippedRows(),
                errors,
                job.getClassificationStatus(),
                job.getClassifiedRows(),
                job.getClosingBalance(),
                job.getStatementFrom(),
                job.getStatementTo(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }

    private static List<RowErrorDto> parseErrors(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<RowError> list = MAPPER.readValue(json, new TypeReference<>() {});
            return list.stream()
                    .map(e -> new RowErrorDto(e.rowNumber(), e.errorMessage(), e.snippet()))
                    .toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
