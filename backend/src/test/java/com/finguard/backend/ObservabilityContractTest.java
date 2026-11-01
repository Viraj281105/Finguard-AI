package com.finguard.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finguard.backend.common.observability.TraceContext;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.ingestion.ImportApiTest;
import com.finguard.backend.support.TestPostgres;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end observability contract tests (S1-01 — Project E):
 * <ul>
 *   <li>{@code /actuator/prometheus} is exposed on the isolated management port ({@code :8081})
 *       and rejected ({@code 401}) on the main application port ({@code :8080}).</li>
 *   <li>W3C {@code traceparent} and {@code X-Request-Id} headers propagate from the HTTP API entry
 *       point across the asynchronous {@code import-*} worker thread to the outbound AI service HTTP call.</li>
 *   <li>Privacy redaction invariant: statement imports and API queries never leak transaction
 *       narration text, raw email addresses, or passwords into captured logs or Prometheus metrics,
 *       while correlating authenticated requests via a 12-hex SHA-256 {@code user_hash}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "management.server.port=0")
class ObservabilityContractTest {

    private static HttpServer mockAiServer;
    private static final AtomicReference<String> lastAiTraceparent = new AtomicReference<>();
    private static final AtomicReference<String> lastAiRequestId = new AtomicReference<>();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository userRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        ensureMockAiServer();
        registry.add("ai.service.url", () -> "http://127.0.0.1:" + mockAiServer.getAddress().getPort());
    }

    private static synchronized void ensureMockAiServer() {
        if (mockAiServer != null) {
            return;
        }
        try {
            mockAiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockAiServer.createContext("/v1/classify", exchange -> {
                lastAiTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                lastAiRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
                byte[] body = "{\"model_version\":\"rules-1.0.0\",\"results\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            mockAiServer.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mock AI server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        if (mockAiServer != null) {
            mockAiServer.stop(0);
            mockAiServer = null;
        }
    }

    @BeforeEach
    void attachLogAppender() {
        lastAiTraceparent.set(null);
        lastAiRequestId.set(null);
        rootLogger = (Logger) LoggerFactory.getLogger("com.finguard.backend");
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (rootLogger != null && logAppender != null) {
            rootLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void prometheusMetricsEndpointIsExposedOnlyOnIsolatedManagementPort() throws Exception {
        HttpResponse<String> onMainPort = httpGet(serverPort, "/actuator/prometheus");
        assertThat(onMainPort.statusCode())
                .as("/actuator/prometheus must be rejected on main application port")
                .isEqualTo(401);

        HttpResponse<String> onMgmtPort = httpGet(managementPort, "/actuator/prometheus");
        assertThat(onMgmtPort.statusCode()).isEqualTo(200);
        assertThat(onMgmtPort.body())
                .contains("finguard_ledger_reconciler_delta_paise")
                .contains("finguard_outbox_oldest_pending_seconds");
    }

    @Test
    void traceparentAndRedactedCorrelationPropagateAcrossHttpImportWorkerAndAiServiceWithoutLeakingPii() throws Exception {
        String email = "obs-" + UUID.randomUUID() + "@example.com";
        String password = "SecretObsPassword!99";
        String incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String incomingParentSpan = "00f067aa0ba902b7";
        String incomingTraceparent = "00-" + incomingTraceId + "-" + incomingParentSpan + "-01";
        String incomingRequestId = "req-s1-01-trace-test";

        MvcResult regResult = mvc.perform(post("/api/auth/register")
                        .header("traceparent", incomingTraceparent)
                        .header("X-Request-Id", incomingRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "name", "Obs Tester",
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(regResult.getResponse().getHeader("traceparent"))
                .startsWith("00-" + incomingTraceId + "-");
        assertThat(regResult.getResponse().getHeader("X-Request-Id"))
                .isEqualTo(incomingRequestId);

        JsonNode authNode = json.readTree(regResult.getResponse().getContentAsString());
        String token = authNode.get("accessToken").asText();
        String userId = userRepository.findByEmail(email).orElseThrow().getId();
        String expectedUserHash = TraceContext.hashUserId(userId);
        assertThat(expectedUserHash).matches("^[0-9a-f]{12}$");

        MockMultipartFile file = ImportApiTest.csv("hdfc.csv");
        MvcResult uploadResult = mvc.perform(multipart("/api/upload/csv")
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .header("traceparent", incomingTraceparent)
                        .header("X-Request-Id", incomingRequestId))
                .andExpect(status().isAccepted())
                .andReturn();

        String jobId = json.readTree(uploadResult.getResponse().getContentAsString()).get("jobId").asText();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            MvcResult statusRes = mvc.perform(get("/api/upload/status/" + jobId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            String st = json.readTree(statusRes.getResponse().getContentAsString()).get("status").asText();
            assertThat(st).isEqualTo("COMPLETED");
        });

        // 1. Outbound AI service call received the exact same 32-hex W3C trace_id and X-Request-Id
        assertThat(lastAiTraceparent.get())
                .as("AI service call from async import-* worker must carry the originating W3C trace_id")
                .isNotNull()
                .startsWith("00-" + incomingTraceId + "-")
                .endsWith("-01")
                .isNotEqualTo(incomingTraceparent); // child span ID minted on worker/outbound call
        assertThat(lastAiRequestId.get()).isEqualTo(incomingRequestId);

        // 2. Async import worker log entries carry trace_id and hashed user_hash in MDC
        List<ILoggingEvent> eventsSnapshot = new CopyOnWriteArrayList<>(logAppender.list);
        assertThat(eventsSnapshot)
                .anySatisfy(event -> {
                    assertThat(event.getFormattedMessage()).contains("[Job " + jobId + "] -> COMPLETED");
                    assertThat(event.getMDCPropertyMap()).containsEntry("trace_id", incomingTraceId);
                    assertThat(event.getMDCPropertyMap()).containsEntry("request_id", incomingRequestId);
                    assertThat(event.getMDCPropertyMap()).containsEntry("user_hash", expectedUserHash);
                });

        // 3. Privacy redaction invariant: no log message or MDC property contains narration text, raw email, or password
        for (ILoggingEvent event : eventsSnapshot) {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .doesNotContain("UPI-SWIGGY-swiggy@icici")
                    .doesNotContain("NEFT-ACME LTD-SALARY MARCH")
                    .doesNotContain("ATM-CASH-KORAMANGALA")
                    .doesNotContain(email)
                    .doesNotContain(password)
                    .doesNotContain(userId);
            String mdcDump = event.getMDCPropertyMap().toString();
            assertThat(mdcDump)
                    .doesNotContain(email)
                    .doesNotContain(password)
                    .doesNotContain(userId);
        }

        // 4. Prometheus metrics record the import & AI batch SLIs and never leak PII or narration text
        HttpResponse<String> metricsRes = httpGet(managementPort, "/actuator/prometheus");
        assertThat(metricsRes.statusCode()).isEqualTo(200);
        String prometheusBody = metricsRes.body();
        assertThat(prometheusBody)
                .contains("finguard_import_jobs_total{application=\"backend\",status=\"COMPLETED\"}")
                .contains("finguard_import_duration_seconds_count{application=\"backend\",status=\"COMPLETED\"}")
                .contains("finguard_ai_classify_batches_total{application=\"backend\",outcome=\"success\"}")
                .doesNotContain("SWIGGY")
                .doesNotContain("SALARY")
                .doesNotContain(email)
                .doesNotContain(userId);
    }

    private HttpResponse<String> httpGet(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
