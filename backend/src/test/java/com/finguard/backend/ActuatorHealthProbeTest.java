package com.finguard.backend;

import com.finguard.backend.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "management.server.port=0")
class ActuatorHealthProbeTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Test
    void actuatorEndpointsAreRejectedOnMainApplicationPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);

        for (String path : new String[]{
                "/actuator",
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness"
        }) {
            HttpResponse<String> response = get(serverPort, path);
            assertThat(response.statusCode())
                    .as("Main port must not expose %s publicly", path)
                    .isEqualTo(401);
        }
    }

    @Test
    void livenessAndReadinessReturnUpOnManagementPortWhenHealthy() throws Exception {
        HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");

        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readinessReturns503WhenDatabaseIsUnreachableWhileLivenessRemains200() throws Exception {
        DriverManagerDataSource unreachableDb = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/unreachable?connectTimeout=1",
                "postgres",
                "postgres"
        );
        HealthContributor original = healthContributorRegistry.unregisterContributor("db");
        healthContributorRegistry.registerContributor("db", new DataSourceHealthIndicator(unreachableDb, "SELECT 1"));
        try {
            HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(readiness.body()).isEqualTo("{\"status\":\"DOWN\"}");

            HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness");
            assertThat(liveness.statusCode()).isEqualTo(200);
            assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
        } finally {
            healthContributorRegistry.unregisterContributor("db");
            if (original != null) {
                healthContributorRegistry.registerContributor("db", original);
            }
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
