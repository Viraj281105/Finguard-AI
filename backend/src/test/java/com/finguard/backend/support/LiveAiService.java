package com.finguard.backend.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Starts the real Python AI service (uvicorn) once per test JVM so the Java client and the full
 * import pipeline can be tested against it, not just against mocks.
 *
 * <p>The interpreter is taken from {@code FINGUARD_AI_PYTHON}, or from {@code ai/.venv}. When none
 * is available the live tests are skipped, unless {@code FINGUARD_REQUIRE_LIVE_AI=true} (set in CI),
 * in which case they fail so the contract can never be skipped silently.
 */
public final class LiveAiService {

    /** The shared secret the live service is started with. */
    public static final String TOKEN = "live-test-token";

    private static final Path AI_DIR = Path.of("..", "ai").toAbsolutePath().normalize();
    private static Optional<String> url;

    private LiveAiService() {
    }

    public static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("FINGUARD_REQUIRE_LIVE_AI"));
    }

    /** Base URL of the running service, or empty if it cannot be started on this machine. */
    public static synchronized Optional<String> url() {
        if (url == null) {
            url = start();
        }
        return url;
    }

    private static Optional<String> start() {
        Optional<String> python = findPython();
        if (python.isEmpty()) {
            return Optional.empty();
        }
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Path log = Files.createTempFile("finguard-ai-live", ".log");
            ProcessBuilder builder = new ProcessBuilder(python.get(), "-m", "uvicorn", "app.main:app",
                    "--host", "127.0.0.1", "--port", String.valueOf(port))
                    .directory(AI_DIR.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("AI_INTERNAL_TOKEN", TOKEN);
            Process process = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));

            String base = "http://127.0.0.1:" + port;
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return Optional.of(base);
                    }
                } catch (IOException e) {
                    // not up yet
                }
                Thread.sleep(250);
            }
            process.destroyForcibly();
            System.err.println("Live AI service did not start; log: " + Files.readString(log));
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            System.err.println("Live AI service could not be started: " + e);
            return Optional.empty();
        }
    }

    private static Optional<String> findPython() {
        String fromEnv = System.getenv("FINGUARD_AI_PYTHON");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv);
        }
        for (String candidate : new String[]{".venv/Scripts/python.exe", ".venv/bin/python"}) {
            Path path = AI_DIR.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toString());
            }
        }
        return Optional.empty();
    }
}
