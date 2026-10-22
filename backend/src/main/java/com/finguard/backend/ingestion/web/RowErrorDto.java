package com.finguard.backend.ingestion.web;

/** JSON DTO for a row-level parsing error. */
public record RowErrorDto(
        int rowNumber,
        String errorMessage,
        String snippet
) {}
