package com.finguard.backend.dto;

import com.finguard.backend.model.Investment.InvestmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvestmentDTO {

    @NotBlank
    private String name;

    @NotNull
    private InvestmentType type;

    @NotNull
    @Positive
    private BigDecimal amountInvested;

    private BigDecimal currentValue;
    private String notes;
}