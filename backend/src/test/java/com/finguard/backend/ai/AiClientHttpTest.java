package com.finguard.backend.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP: timeouts, refused connections and the shared-secret header, with the production RestClient. */
class AiClientHttpTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiClient clientFor(String url, String token, Duration readTimeout) {
        AiProperties properties = new AiProperties(url, Duration.ofMillis(500), readTimeout, 200, token,
                Duration.ofSeconds(30));
        return new AiClient(new AiConfig(properties).aiRestClient(), properties);
    }

    private String start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/classify", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static List<ClassifyRequest.Item> oneItem() {
        return List.of(new ClassifyRequest.Item("t1", "ZOMATO", "EXPENSE", new BigDecimal("10")));
    }

    @Test
    void sendsTheSharedSecretHeaderWhenConfiguredAndParsesTheAnswer() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        String url = start(exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = ("{\"model_version\":\"v\",\"results\":[{\"id\":\"t1\",\"category\":\"DINING\","
                    + "\"confidence\":0.75,\"method\":\"rule\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ClassifyOutcome outcome = clientFor(url, "s3cret", Duration.ofSeconds(5)).classify(oneItem());

        assertThat(token.get()).isEqualTo("s3cret");
        assertThat(outcome.predictions().get("t1").category()).isEqualTo("DINING");
    }

    @Test
    void noHeaderIsSentWithoutAConfiguredSecret() throws Exception {
        AtomicReference<String> token = new AtomicReference<>("unset");
        String url = start(exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        clientFor(url, "", Duration.ofSeconds(5)).classify(oneItem());

        assertThat(token.get()).isNull();
    }

    @Test
    void aSlowServiceIsCutOffByTheReadTimeoutAndReportedUnavailable() throws Exception {
        String url = start(exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        long start = System.nanoTime();
        ClassifyOutcome outcome = clientFor(url, "", Duration.ofMillis(300)).classify(oneItem());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome.unavailable()).isTrue();
        assertThat(elapsedMs).as("must give up near the 300 ms read timeout, not wait for the 3 s response")
                .isLessThan(2500);
    }

    @Test
    void aClosedPortIsReportedUnavailableWithoutAnException() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        ClassifyOutcome outcome = clientFor("http://127.0.0.1:" + freePort, "", Duration.ofSeconds(1)).classify(oneItem());

        assertThat(outcome.unavailable()).isTrue();
    }

    @Test
    void aServiceThatSendsHtmlInsteadOfJsonIsTreatedAsFailed() throws Exception {
        String url = start(exchange -> {
            byte[] body = "<html>gateway error</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThat(clientFor(url, "", Duration.ofSeconds(5)).classify(oneItem()).unavailable()).isTrue();
    }
}
