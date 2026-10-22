package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * Computes a deterministic SHA-256 fingerprint for user-scoped transaction deduplication.
 * Includes user id, date, direction, scaled amount, normalized description, and intra-file occurrence index.
 */
public final class TransactionFingerprint {

    private TransactionFingerprint() {}

    public static String compute(
            String userId,
            LocalDate date,
            TransactionType type,
            BigDecimal amount,
            String title,
            int occurrenceIndex
    ) {
        String normalizedTitle = (title == null ? "" : title.trim().toLowerCase().replaceAll("\\s+", " "));
        String amountStr = (amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        String dateStr = (date == null ? "" : date.toString());
        String typeStr = (type == null ? "" : type.name());
        String userStr = (userId == null ? "" : userId);

        String raw = userStr + "|" + dateStr + "|" + typeStr + "|" + amountStr + "|" + normalizedTitle + "|" + occurrenceIndex;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
