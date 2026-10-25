package com.finguard.backend.forecasting.service;

import com.finguard.backend.common.error.ApiException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard reducing-balance loan arithmetic in {@link BigDecimal}. Pure and deterministic.
 *
 * <p>Simplifications (docs/FINANCIAL_RULES.md section 8): interest accrues monthly at annualRate / 12 on the opening balance;
 * the instalment is paid at the end of the month; a prepayment is applied at the start of month 1 and shortens the
 * tenure (the instalment is unchanged); no prepayment charges, fees, floating-rate resets or day-count conventions are
 * modelled. Lenders differ, so results are estimates, not a lender statement.
 */
public final class LoanAmortization {

    static final int MAX_MONTHS = 1200;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private LoanAmortization() {
    }

    /** Outcome of running a loan forward: the payments in the first {@code horizon} months and the whole remaining life. */
    public record Run(List<BigDecimal> payments, int monthsToPayoff, BigDecimal totalInterest, BigDecimal totalPaid) {
    }

    static BigDecimal monthlyRate(BigDecimal annualPercent) {
        return annualPercent.divide(BigDecimal.valueOf(1200), MC);
    }

    /** The EMI that repays {@code principal} over {@code months} at {@code annualPercent}: P*r*(1+r)^n / ((1+r)^n - 1). */
    public static BigDecimal emi(BigDecimal principal, BigDecimal annualPercent, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        BigDecimal r = monthlyRate(annualPercent);
        if (r.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        return principal.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC).setScale(2, RoundingMode.HALF_UP);
    }

    /** Balance after {@code months} regular instalments (never negative). */
    public static BigDecimal balanceAfter(BigDecimal principal, BigDecimal annualPercent, BigDecimal emi, int months) {
        BigDecimal r = monthlyRate(annualPercent);
        BigDecimal balance = principal;
        for (int i = 0; i < months && balance.signum() > 0; i++) {
            balance = balance.add(balance.multiply(r, MC)).subtract(emi).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        }
        return balance;
    }

    /**
     * Runs the loan from {@code balance}. {@code lumpSum} (>= 0) is paid at the start of month 1 and capped at the balance.
     * Throws 400 when the instalment does not even cover the first month's interest (the loan would never be repaid).
     */
    public static Run run(BigDecimal balance, BigDecimal annualPercent, BigDecimal emi, BigDecimal lumpSum, int horizon) {
        BigDecimal r = monthlyRate(annualPercent);
        BigDecimal bal = balance;
        BigDecimal lump = lumpSum.min(bal).max(BigDecimal.ZERO);
        List<BigDecimal> payments = new ArrayList<>();
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        int months = 0;
        bal = bal.subtract(lump);
        while (bal.signum() > 0) {
            if (months >= MAX_MONTHS) {
                throw ApiException.badRequest("With this instalment the loan is not repaid within 100 years; the EMI must exceed the monthly interest.");
            }
            BigDecimal monthInterest = bal.multiply(r, MC).setScale(2, RoundingMode.HALF_UP);
            if (months == 0 && emi.compareTo(monthInterest) <= 0) {
                throw ApiException.badRequest("The EMI (₹" + emi.toPlainString() + ") does not cover the monthly interest (₹"
                        + monthInterest.toPlainString() + "), so the balance would never fall.");
            }
            BigDecimal payment = emi.min(bal.add(monthInterest));
            bal = bal.add(monthInterest).subtract(payment);
            interest = interest.add(monthInterest);
            BigDecimal total = payment.add(months == 0 ? lump : BigDecimal.ZERO);
            paid = paid.add(total);
            if (payments.size() < horizon) {
                payments.add(total);
            }
            months++;
        }
        if (months == 0) { // fully repaid by the lump sum
            paid = lump;
            if (horizon > 0) {
                payments.add(lump);
            }
        }
        while (payments.size() < horizon) {
            payments.add(BigDecimal.ZERO);
        }
        return new Run(List.copyOf(payments), months, interest, paid);
    }
}
