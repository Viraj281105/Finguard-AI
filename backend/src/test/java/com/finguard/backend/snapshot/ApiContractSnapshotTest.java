package com.finguard.backend.snapshot;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Wire-contract snapshot of the HTTP API (platform-upgrade safety net, roadmap S0-03).
 *
 * <p>Drives one deterministic scenario (fixed clock, synthetic data) through every documented endpoint plus the error and
 * CORS behaviour the end-to-end audit found, normalises ids, timestamps and tokens, and compares the result with
 * {@code src/test/resources/snapshots/api-contract.txt}. Money is compared as the exact number text the server wrote
 * ({@code 1450.50} is not {@code 1450.5}), object keys are sorted so that a framework that orders properties differently
 * does not cause noise, and array order is kept.
 *
 * <p>This class deliberately uses no JSON library of its own, so it runs unchanged across Jackson major versions.
 * To accept an intended change: {@code mvnw test -Dtest=ApiContractSnapshotTest -Dsnapshot.update=true} and review the diff.
 */
@Import(ApiContractSnapshotTest.FixedClock.class)
class ApiContractSnapshotTest extends IntegrationTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock snapshotClock() {
            return Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final Path GOLDEN = Path.of("src/test/resources/snapshots/api-contract.txt");
    private static final Path ACTUAL = Path.of("target/api-contract.actual.txt");
    private static final List<String> KEPT_HEADERS = List.of("Content-Type", "X-Total-Count", "X-Page", "X-Page-Size",
            "X-Total-Pages", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Content-Security-Policy",
            "Access-Control-Allow-Origin", "Access-Control-Allow-Methods", "Access-Control-Allow-Headers",
            "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers", "Access-Control-Max-Age",
            "Retry-After", "Location", "Allow");

    private final StringBuilder out = new StringBuilder();
    private TestUser user;

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theHttpContractMatchesTheCommittedSnapshot() throws Exception {
        user = registerUser(); // not recorded: the email and tokens are random

        // ---- authentication
        String email = "snapshot-user@example.com";
        record("POST /api/auth/register", post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Snapshot User\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
        record("POST /api/auth/register (duplicate email)", post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Snapshot User\",\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}"));
        record("POST /api/auth/register (password too short)", post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"email\":\"short@example.com\",\"password\":\"abc\"}"));
        record("POST /api/auth/login", post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
        record("POST /api/auth/login (wrong password)", post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"definitely-wrong-1\"}"));
        record("POST /api/auth/refresh (no cookie)", post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"));

        // ---- empty-account responses
        for (String url : List.of("/api/transactions", "/api/loans", "/api/investments", "/api/goals", "/api/scenarios",
                "/api/upload/jobs", "/api/analytics/summary", "/api/forecast/cash-flow", "/api/recommendations")) {
            record("GET " + url + " (empty account)", as(get(url)));
        }

        // ---- ledger data: six synthetic months, a loan, an investment
        record("POST /api/transactions (income)", as(post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Salary Jan\",\"amount\":50000.00,\"type\":\"INCOME\",\"date\":\"2026-01-01\",\"category\":\"INCOME\"}"));
        for (int m = 1; m <= 6; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            if (m > 1) {
                add("Salary " + ym, ym.atDay(1), "INCOME", "INCOME", "50000.00");
            }
            add("Landlord " + ym, ym.atDay(3), "EXPENSE", "HOUSING", "20000.00");
            add("Market " + ym, ym.atDay(15), "EXPENSE", "GROCERIES", "10000.50");
        }
        add("Mystery payee", java.time.LocalDate.of(2026, 6, 20), "EXPENSE", null, "1450.75");
        record("POST /api/transactions (validation error)", as(post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        record("POST /api/transactions (malformed JSON)", as(post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":"));
        record("POST /api/transactions (unsupported media type)", as(post("/api/transactions")).contentType(MediaType.TEXT_PLAIN)
                .content("hello"));
        String loanId = id(record("POST /api/loans", as(post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Home loan\",\"type\":\"HOME\",\"principal\":2500000.00,\"emiAmount\":24000.00,"
                        + "\"interestRate\":8.5,\"startDate\":\"2025-01-01\",\"endDate\":\"2045-01-01\",\"active\":true}")));
        String investmentId = id(record("POST /api/investments", as(post("/api/investments")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Index fund\",\"type\":\"MUTUAL_FUND\",\"amountInvested\":100000.00,\"currentValue\":112500.25,\"notes\":\"snapshot\"}")));

        // ---- statement import (AI service unreachable in tests: classification falls back to UNAVAILABLE)
        String jobId = idOfJson(recordRaw("POST /api/upload/csv (hdfc)", as(multipart("/api/upload/csv").file(fixture("hdfc.csv"))), 202), "jobId");
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            String body = mvc.perform(as(get("/api/upload/status/" + jobId))).andReturn().getResponse().getContentAsString();
            assertThat(body).containsAnyOf("\"COMPLETED\"", "\"FAILED\"");
        });
        record("GET /api/upload/status/{jobId}", as(get("/api/upload/status/" + jobId)));
        record("GET /api/upload/status/{unknown}", as(get("/api/upload/status/does-not-exist")));
        record("POST /api/upload/csv (no multipart body)", as(post("/api/upload/csv")).contentType(MediaType.APPLICATION_JSON).content("{}"));

        // ---- ledger reads and updates
        record("GET /api/transactions (page 0)", as(get("/api/transactions")));
        record("GET /api/transactions?page=1&size=5", as(get("/api/transactions?page=1&size=5")));
        String txId = firstId(recordRaw("GET /api/transactions?unpaged=true", as(get("/api/transactions?unpaged=true")), 200));
        record("GET /api/transactions/{id}", as(get("/api/transactions/" + txId)));
        record("GET /api/transactions/{unknown}", as(get("/api/transactions/does-not-exist")));
        record("PATCH /api/transactions/{id}/category", as(patch("/api/transactions/" + txId + "/category"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"DINING\"}"));
        record("POST /api/transactions/reclassify", as(post("/api/transactions/reclassify")));
        record("GET /api/loans", as(get("/api/loans")));
        record("GET /api/loans/{id}", as(get("/api/loans/" + loanId)));
        record("PUT /api/loans/{id}", as(put("/api/loans/" + loanId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Home loan\",\"type\":\"HOME\",\"principal\":2500000.00,\"emiAmount\":25000.00,"
                        + "\"interestRate\":8.5,\"startDate\":\"2025-01-01\",\"endDate\":\"2045-01-01\",\"active\":true}"));
        record("GET /api/investments", as(get("/api/investments")));
        record("PUT /api/investments/{id}", as(put("/api/investments/" + investmentId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Index fund\",\"type\":\"MUTUAL_FUND\",\"amountInvested\":100000.00,\"currentValue\":115000.00,\"notes\":\"updated\"}"));

        // ---- analytics
        for (String path : List.of("summary", "monthly", "categories", "recurring", "data-quality", "insights")) {
            record("GET /api/analytics/" + path, as(get("/api/analytics/" + path)));
        }
        record("GET /api/analytics/summary?from=2026-03-01&to=2026-04-30", as(get("/api/analytics/summary?from=2026-03-01&to=2026-04-30")));
        record("GET /api/analytics/summary (bad date)", as(get("/api/analytics/summary?from=not-a-date")));

        // ---- budgets and goals
        record("POST /api/budgets (overall)", as(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"yearMonth\":\"2026-07\",\"amount\":45000.00}"));
        String budgetId = id(record("POST /api/budgets (category)", as(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"yearMonth\":\"2026-07\",\"category\":\"GROCERIES\",\"amount\":12000.00}")));
        record("POST /api/budgets (duplicate)", as(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"yearMonth\":\"2026-07\",\"category\":\"GROCERIES\",\"amount\":13000.00}"));
        record("GET /api/budgets", as(get("/api/budgets")));
        record("GET /api/budgets/overview?yearMonth=2026-06", as(get("/api/budgets/overview?yearMonth=2026-06")));
        record("GET /api/budgets/overview?month=2026-07 (legacy alias)", as(get("/api/budgets/overview?month=2026-07")));
        record("PUT /api/budgets/{id}", as(put("/api/budgets/" + budgetId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":12500.00}"));
        String goalId = id(record("POST /api/goals", as(post("/api/goals")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Emergency fund\",\"description\":\"six months\",\"targetAmount\":300000.00,\"targetDate\":\"2027-12-31\"}")));
        record("POST /api/goals/{id}/contributions", as(post("/api/goals/" + goalId + "/contributions"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":15000.25,\"contributionDate\":\"2026-07-10\",\"note\":\"first\"}"));
        record("GET /api/goals", as(get("/api/goals")));
        record("GET /api/goals/{id}", as(get("/api/goals/" + goalId)));

        // ---- forecasting, scenarios, recommendations
        for (String path : List.of("cash-flow", "categories", "budget-overruns", "savings", "backtest")) {
            record("GET /api/forecast/" + path, as(get("/api/forecast/" + path)));
        }
        record("GET /api/forecast/cash-flow?horizonMonths=99", as(get("/api/forecast/cash-flow?horizonMonths=99")));
        record("POST /api/forecast/scenarios/simulate (empty scenario)", as(post("/api/forecast/scenarios/simulate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"baseline\",\"horizonMonths\":3}"));
        String scenarioId = id(record("POST /api/scenarios", as(post("/api/scenarios")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"snapshot scenario\",\"horizonMonths\":3}")));
        record("GET /api/scenarios", as(get("/api/scenarios")));
        record("GET /api/scenarios/{id}", as(get("/api/scenarios/" + scenarioId)));
        record("POST /api/scenarios/{id}/rerun", as(post("/api/scenarios/" + scenarioId + "/rerun")));
        record("GET /api/recommendations", as(get("/api/recommendations")));

        // ---- transport behaviour: authentication, routing errors, CORS (found by the end-to-end audit)
        record("GET /api/transactions (no token)", get("/api/transactions"));
        record("GET /api/transactions (garbage token)", get("/api/transactions").header("Authorization", "Bearer not.a.jwt"));
        record("GET /api/does-not-exist", as(get("/api/does-not-exist")));
        record("DELETE /api/forecast/cash-flow (method not allowed)", as(delete("/api/forecast/cash-flow")));
        record("GET /api/transactions with Origin (allowed)", as(get("/api/transactions")).header("Origin", "http://localhost:5173"));
        record("GET /api/transactions with Origin (not allowed)", as(get("/api/transactions")).header("Origin", "http://evil.example"));
        record("OPTIONS /api/transactions preflight (allowed)", options("/api/transactions").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"));

        // ---- clean-up of mutable resources, then compare
        record("DELETE /api/scenarios/{id}", as(delete("/api/scenarios/" + scenarioId)));
        record("DELETE /api/budgets/{id}", as(delete("/api/budgets/" + budgetId)));
        record("DELETE /api/loans/{id}", as(delete("/api/loans/" + loanId)));

        compareWithGolden(out.toString());
    }

    // ---- request helpers ---------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return as(user, request);
    }

    private void add(String title, java.time.LocalDate date, String type, String category, String amount) throws Exception {
        String categoryJson = category == null ? "" : ",\"category\":\"" + category + "\"";
        mvc.perform(as(post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\",\"date\":\"" + date + "\"" + categoryJson + "}"));
    }

    private static MockMultipartFile fixture(String name) throws IOException {
        try (var in = ApiContractSnapshotTest.class.getResourceAsStream("/fixtures/statements/" + name)) {
            return new MockMultipartFile("file", name, "text/csv", in.readAllBytes());
        }
    }

    /** Performs the request, records the normalised exchange and returns the raw response body. */
    private String record(String label, MockHttpServletRequestBuilder request) throws Exception {
        return recordRaw(label, request, -1);
    }

    private String recordRaw(String label, MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        if (expectedStatus > 0) {
            assertThat(response.getStatus()).as(label).isEqualTo(expectedStatus);
        }
        out.append("### ").append(label).append('\n');
        out.append("status: ").append(response.getStatus()).append('\n');
        for (String header : KEPT_HEADERS) {
            for (String value : response.getHeaders(header)) {
                out.append("header ").append(header).append(": ").append(normalizeText(value)).append('\n');
            }
        }
        for (String cookie : response.getHeaders("Set-Cookie")) {
            out.append("header Set-Cookie: ").append(normalizeCookie(cookie)).append('\n');
        }
        out.append("body: ").append(body.isBlank() ? "<empty>" : canonicalJsonOrText(body)).append("\n\n");
        return body;
    }

    private static String id(String body) {
        return idOfJson(body, "id");
    }

    private static String idOfJson(String body, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("field " + field + " in " + body).isTrue();
        return m.group(1);
    }

    private static String firstId(String body) {
        return id(body);
    }

    // ---- normalisation -----------------------------------------------------------------------------------------------

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern TEST_EMAIL = Pattern.compile("user-<uuid>@example\\.com");

    static String normalizeText(String text) {
        String s = JWT.matcher(text).replaceAll("<jwt>");
        s = UUID.matcher(s).replaceAll("<uuid>");
        s = TIMESTAMP.matcher(s).replaceAll("<timestamp>");
        return TEST_EMAIL.matcher(s).replaceAll("<email>");
    }

    private static String normalizeCookie(String cookie) {
        String[] parts = cookie.split(";\\s*");
        List<String> kept = new ArrayList<>();
        kept.add(parts[0].replaceFirst("=.*", "=<value>"));
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i];
            if (attribute.toLowerCase().startsWith("expires=")) {
                continue; // wall-clock dependent
            }
            kept.add(attribute);
        }
        kept.sort(String::compareToIgnoreCase);
        return String.join("; ", kept);
    }

    private static String canonicalJsonOrText(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object parsed = new MiniJson(trimmed).parse();
                StringBuilder sb = new StringBuilder();
                MiniJson.print(parsed, sb, 0);
                return normalizeText(sb.toString());
            } catch (RuntimeException e) {
                return "<unparseable JSON: " + e.getMessage() + "> " + normalizeText(trimmed);
            }
        }
        return normalizeText(trimmed);
    }

    private static void compareWithGolden(String actual) throws IOException {
        if (Boolean.getBoolean("snapshot.update")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(GOLDEN)).as("golden snapshot %s exists (create it with -Dsnapshot.update=true)", GOLDEN).isTrue();
        String expected = Files.readString(GOLDEN, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String normalizedActual = actual.replace("\r\n", "\n");
        if (!expected.equals(normalizedActual)) {
            Files.createDirectories(ACTUAL.getParent());
            Files.writeString(ACTUAL, normalizedActual, StandardCharsets.UTF_8);
            String[] e = expected.split("\n", -1);
            String[] a = normalizedActual.split("\n", -1);
            int line = 0;
            while (line < e.length && line < a.length && e[line].equals(a[line])) {
                line++;
            }
            String section = "";
            for (int i = Math.min(line, a.length - 1); i >= 0; i--) {
                if (a[i].startsWith("### ")) {
                    section = a[i];
                    break;
                }
            }
            throw new AssertionError("API contract changed at line " + (line + 1) + " in section [" + section + "]\n  expected: "
                    + (line < e.length ? e[line] : "<end>") + "\n  actual:   " + (line < a.length ? a[line] : "<end>")
                    + "\nFull actual output: " + ACTUAL.toAbsolutePath());
        }
    }

    // ---- a tiny JSON reader/printer that keeps number literals verbatim -----------------------------------------------

    /** Raw literal (number, true, false, null) exactly as written. */
    private record Literal(String text) {
    }

    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object parse() {
            Object value = value();
            skip();
            if (i != s.length()) {
                throw new IllegalStateException("trailing characters at " + i);
            }
            return value;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> map = new TreeMap<>();
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    skip();
                    String key = string();
                    skip();
                    expect(':');
                    map.put(key, value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return new Literal(s.substring(start, i));
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            i++;
            return sb.toString();
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("expected '" + c + "' at " + i);
            }
            i++;
        }

        @SuppressWarnings("unchecked")
        static void print(Object value, StringBuilder sb, int depth) {
            String pad = "  ".repeat(depth + 1);
            if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int n = 0;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                    sb.append(pad).append(quote(e.getKey())).append(": ");
                    print(e.getValue(), sb, depth + 1);
                    sb.append(++n < map.size() ? ",\n" : "\n");
                }
                sb.append("  ".repeat(depth)).append('}');
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                for (int n = 0; n < list.size(); n++) {
                    sb.append(pad);
                    print(list.get(n), sb, depth + 1);
                    sb.append(n + 1 < list.size() ? ",\n" : "\n");
                }
                sb.append("  ".repeat(depth)).append(']');
            } else if (value instanceof Literal literal) {
                sb.append(literal.text());
            } else {
                sb.append(quote(String.valueOf(value)));
            }
        }

        private static String quote(String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
    }
}
