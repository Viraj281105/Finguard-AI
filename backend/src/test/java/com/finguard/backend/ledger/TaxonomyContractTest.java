package com.finguard.backend.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.backend.ledger.transactions.AutomaticCategory;
import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Keeps the Java taxonomy identical to contracts/categories.json (also checked in Python, SQL and TypeScript). */
class TaxonomyContractTest {

    static JsonNode contract() throws IOException {
        Path path = Path.of("..", "contracts", "categories.json");
        return new ObjectMapper().readTree(Files.readString(path));
    }

    @Test
    void javaEnumMatchesTheSharedContract() throws IOException {
        JsonNode categories = contract().get("categories");

        List<String> ids = new ArrayList<>();
        for (JsonNode c : categories) {
            ids.add(c.get("id").asText());
            TransactionCategory category = TransactionCategory.valueOf(c.get("id").asText());
            assertThat(category.flow().name()).isEqualTo(c.get("flow").asText());
            Set<String> types = StreamSupport.stream(c.get("types").spliterator(), false)
                    .map(JsonNode::asText).collect(Collectors.toSet());
            for (TransactionType type : TransactionType.values()) {
                assertThat(category.allowedFor(type)).as(category + " for " + type).isEqualTo(types.contains(type.name()));
            }
        }
        assertThat(java.util.Arrays.stream(TransactionCategory.values()).map(Enum::name)).containsExactlyElementsOf(ids);
    }

    @Test
    void theMigrationConstraintListsExactlyTheSameIdentifiers() throws IOException {
        String sql = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V5__transaction_categorisation.sql"));
        String constraint = sql.substring(sql.indexOf("ADD CONSTRAINT ck_transactions_category CHECK"),
                sql.indexOf("ck_transactions_category_provenance"));

        Set<String> inSql = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(constraint);
        while (m.find()) {
            inSql.add(m.group(1));
        }
        Set<String> inContract = StreamSupport.stream(contract().get("categories").spliterator(), false)
                .map(c -> c.get("id").asText()).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertThat(inSql).isEqualTo(inContract);
    }

    @Test
    void nonSpendingMovementsAreNeverOrdinaryExpenses() {
        for (TransactionCategory c : List.of(TransactionCategory.TRANSFERS, TransactionCategory.CARD_PAYMENTS,
                TransactionCategory.INVESTMENTS)) {
            assertThat(c.flow()).isEqualTo(TransactionCategory.Flow.NON_SPENDING);
        }
        assertThat(TransactionCategory.REFUNDS.flow()).isEqualTo(TransactionCategory.Flow.REFUND);
    }

    @Test
    void parseAcceptsIdsInAnyCaseAndLegacyLabels() {
        assertThat(TransactionCategory.parse("dining")).contains(TransactionCategory.DINING);
        assertThat(TransactionCategory.parse(" Loan Payments ")).contains(TransactionCategory.LOAN_PAYMENTS);
        assertThat(TransactionCategory.parse("Investment Returns")).contains(TransactionCategory.INCOME);
        assertThat(TransactionCategory.parse("Food")).contains(TransactionCategory.DINING);
        assertThat(TransactionCategory.parse("Rent")).contains(TransactionCategory.HOUSING);
        assertThat(TransactionCategory.parse("EMI")).contains(TransactionCategory.LOAN_PAYMENTS);
        assertThat(TransactionCategory.parse("Other")).contains(TransactionCategory.UNCATEGORIZED);
        assertThat(TransactionCategory.parse("nonsense")).isEmpty();
        assertThat(TransactionCategory.parse("  ")).isEmpty();
        assertThat(TransactionCategory.parse(null)).isEmpty();
    }

    @Test
    void anAutomaticCategoryCannotPretendToBeAUserChoiceOrUncategorised() {
        assertThatThrownBy(() -> new AutomaticCategory(TransactionCategory.DINING, CategorySource.USER,
                BigDecimal.ONE, false, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomaticCategory(TransactionCategory.DINING, CategorySource.NONE,
                BigDecimal.ONE, false, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomaticCategory(TransactionCategory.UNCATEGORIZED, CategorySource.RULE,
                BigDecimal.ONE, false, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
