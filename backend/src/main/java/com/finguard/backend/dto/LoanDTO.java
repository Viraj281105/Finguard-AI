package com.finguard.backend.dto;

import com.finguard.backend.model.Loan.LoanType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LoanDTO {

    @NotBlank
    private String name;

    @NotNull
    private LoanType type;

    @NotNull
    @Positive
    private BigDecimal principal;

    @NotNull
    @Positive
    private BigDecimal emiAmount;

    @NotNull
    @Positive
    private BigDecimal interestRate;

    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active = true;
}