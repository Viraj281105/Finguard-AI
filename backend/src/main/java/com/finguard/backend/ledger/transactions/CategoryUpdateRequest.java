package com.finguard.backend.ledger.transactions;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code PATCH /api/transactions/{id}/category}. */
public record CategoryUpdateRequest(@NotBlank String category) {
}
