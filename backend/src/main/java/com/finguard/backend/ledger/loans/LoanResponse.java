package com.finguard.backend.ledger.loans;

import com.finguard.backend.ledger.loans.Loan.LoanType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LoanResponse(
        String id,
        String name,
        LoanType type,
        BigDecimal principal,
        BigDecimal emiAmount,
        BigDecimal interestRate,
        LocalDate startDate,
        LocalDate endDate,
        Boolean active,
        LocalDateTime createdAt
) {

    public static LoanResponse from(Loan l) {
        return new LoanResponse(l.getId(), l.getName(), l.getType(), l.getPrincipal(), l.getEmiAmount(),
                l.getInterestRate(), l.getStartDate(), l.getEndDate(), l.getActive(), l.getCreatedAt());
    }
}
