package com.finguard.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for tests that need the full application against a real PostgreSQL (Flyway migrations applied,
 * Hibernate in validate mode). Each test registers its own uniquely named users, so tests never
 * depend on a clean database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String PASSWORD = "secret12345";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    /** A registered user and the tokens issued for it. */
    public record TestUser(String email, String accessToken, String refreshToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    protected TestUser registerUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        var mvcResult = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Test User", "email", email, "password", PASSWORD))))
                .andReturn();
        String body = mvcResult.getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        jakarta.servlet.http.Cookie cookie = mvcResult.getResponse().getCookie("refreshToken");
        String refreshToken = cookie != null ? cookie.getValue() : (node.has("refreshToken") ? node.get("refreshToken").asText() : null);
        return new TestUser(email, node.get("accessToken").asText(), refreshToken);
    }

    protected String json(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    protected MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", user.bearer());
    }
}
