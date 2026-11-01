package com.finguard.backend.security;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-06 Automated Authorization Matrix Test (06 §9):
 * Generates an exhaustive security matrix directly from live Spring Boot {@link RequestMappingHandlerMapping}
 * controllers. Verifies that 100% of {@code /api/**} endpoints enforce tenant isolation or required access controls.
 */
@DisplayName("S1-06 — Controller Authorization Matrix & Endpoint Security Gate")
class AuthorizationMatrixTest extends IntegrationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Whitelist of intentionally public endpoints on the primary application port.
     * Every other {@code /api/**} endpoint in the entire application MUST reject unauthenticated requests with 401.
     */
    private static final Set<String> PUBLIC_AUTH_ENDPOINTS = Set.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "POST /api/auth/logout"
    );

    record EndpointEntry(String httpMethod, String pattern, String controllerMethod) implements Comparable<EndpointEntry> {
        public String key() {
            return httpMethod + " " + pattern;
        }

        @Override
        public int compareTo(EndpointEntry o) {
            int cmp = pattern.compareTo(o.pattern);
            return cmp != 0 ? cmp : httpMethod.compareTo(o.httpMethod);
        }
    }

    @Test
    @DisplayName("100% of non-public /api/** endpoints reject unauthenticated access with 401")
    void allNonPublicEndpointsEnforceAuthentication() throws Exception {
        List<EndpointEntry> apiEndpoints = discoverApiEndpoints();

        assertThat(apiEndpoints)
                .as("Authorization matrix must discover all live controllers")
                .isNotEmpty();

        int protectedCount = 0;
        int publicCount = 0;
        List<String> violations = new ArrayList<>();

        for (EndpointEntry endpoint : apiEndpoints) {
            String key = endpoint.key();
            boolean isWhitelistedPublic = PUBLIC_AUTH_ENDPOINTS.contains(key);

            if (isWhitelistedPublic) {
                publicCount++;
                continue;
            }

            protectedCount++;
            String testUri = substitutePathVariables(endpoint.pattern());
            HttpMethod method = HttpMethod.valueOf(endpoint.httpMethod());

            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, URI.create(testUri))
                    .contentType(MediaType.APPLICATION_JSON);

            // Execute without Authorization header
            var result = mvc.perform(request).andReturn();
            int statusCode = result.getResponse().getStatus();

            // Spring Security returns 401 Unauthorized for unauthenticated requests
            if (statusCode != 401) {
                violations.add(String.format("FAIL: [%s] allowed unauthenticated request with HTTP %d (expected 401)",
                        key, statusCode));
            }
        }

        assertThat(violations)
                .as("Protected endpoints failing authentication check")
                .isEmpty();

        assertThat(publicCount)
                .as("Expected exactly 4 public authentication endpoints")
                .isEqualTo(PUBLIC_AUTH_ENDPOINTS.size());

        assertThat(protectedCount)
                .as("Expected at least 50 protected API endpoints across all domains")
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    @DisplayName("100% of protected endpoints reject invalid / forged Bearer tokens with 401")
    void allNonPublicEndpointsRejectInvalidBearerTokens() throws Exception {
        List<EndpointEntry> apiEndpoints = discoverApiEndpoints();

        for (EndpointEntry endpoint : apiEndpoints) {
            String key = endpoint.key();
            if (PUBLIC_AUTH_ENDPOINTS.contains(key)) {
                continue;
            }

            String testUri = substitutePathVariables(endpoint.pattern());
            HttpMethod method = HttpMethod.valueOf(endpoint.httpMethod());

            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, URI.create(testUri))
                    .header("Authorization", "Bearer forged.invalid.token-signature-0123456789")
                    .contentType(MediaType.APPLICATION_JSON);

            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("Matrix completeness check: every discovered controller endpoint is classified")
    void matrixClassifiesAllEndpoints() {
        List<EndpointEntry> endpoints = discoverApiEndpoints();
        Set<String> discoveredKeys = new TreeSet<>();
        for (EndpointEntry e : endpoints) {
            discoveredKeys.add(e.key());
        }

        // Ensure public endpoints actually exist in the discovered controller set
        for (String publicEndpoint : PUBLIC_AUTH_ENDPOINTS) {
            assertThat(discoveredKeys)
                    .as("Public whitelist endpoint '%s' must correspond to an actual controller mapping", publicEndpoint)
                    .contains(publicEndpoint);
        }
    }

    private List<EndpointEntry> discoverApiEndpoints() {
        List<EndpointEntry> endpoints = new ArrayList<>();
        Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            var handlerMethod = entry.getValue();

            Set<String> patterns = mapping.getDirectPaths();
            if (patterns.isEmpty() && mapping.getPathPatternsCondition() != null) {
                patterns = mapping.getPathPatternsCondition().getPatternValues();
            }

            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                methods = Set.of(RequestMethod.GET);
            }

            for (String pattern : patterns) {
                if (pattern.startsWith("/api/")) {
                    for (RequestMethod method : methods) {
                        endpoints.add(new EndpointEntry(
                                method.name(),
                                pattern,
                                handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName()
                        ));
                    }
                }
            }
        }
        Collections.sort(endpoints);
        return endpoints;
    }

    private String substitutePathVariables(String pattern) {
        return pattern.replaceAll("\\{[^}]+\\}", "test-mock-id-00000000");
    }
}
