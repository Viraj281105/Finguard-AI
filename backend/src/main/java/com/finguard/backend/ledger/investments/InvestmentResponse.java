package com.finguard.backend.ledger.investments;

import com.finguard.backend.ledger.investments.Investment.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InvestmentResponse(
        String id,
        String name,
        InvestmentType type,
        BigDecimal amountInvested,
        BigDecimal currentValue,
        String notes,
        LocalDateTime createdAt
) {

    public static InvestmentResponse from(Investment i) {
        return new InvestmentResponse(i.getId(), i.getName(), i.getType(), i.getAmountInvested(),
                i.getCurrentValue(), i.getNotes(), i.getCreatedAt());
    }
}
