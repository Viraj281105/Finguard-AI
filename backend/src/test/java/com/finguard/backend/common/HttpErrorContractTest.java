package com.finguard.backend.common;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the HTTP contract requirements (KI-014, KI-029):
 * 404 for unknown routes, 405 for wrong method, 415 for unsupported media type,
 * 413 for oversized payloads, and presence of standard security headers.
 */
class HttpErrorContractTest extends IntegrationTest {

    @Test
    void unknownPublicRouteReturns404WithStandardApiError() throws Exception {
        mvc.perform(get("/api/auth/nonexistent-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void aParameterOfTheWrongTypeReturns400NotAServerError() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, get("/api/forecast/cash-flow?horizonMonths=abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Parameter 'horizonMonths' has an invalid value"));
    }

    @Test
    void unknownAuthenticatedRouteReturns404WithStandardApiError() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, get("/api/nonexistent-resource")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void unsupportedMethodReturns405WithStandardApiError() throws Exception {
        mvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").value("Method not allowed"));
    }

    @Test
    void unsupportedMediaTypeReturns415WithStandardApiError() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<login><email>a@x.com</email></login>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.message").value("Unsupported media type"));
    }

    @Test
    void securityHeadersArePresentOnResponses() throws Exception {
        mvc.perform(get("/api/auth/nonexistent-route"))
                .andExpect(header().string("X-Content-Type-Options", is("nosniff")))
                .andExpect(header().string("X-Frame-Options", is("DENY")))
                .andExpect(header().string("Referrer-Policy", containsString("strict-origin-when-cross-origin")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    @Test
    void oversizedFileUploadReturns413PayloadTooLarge() throws Exception {
        TestUser user = registerUser();
        // 6 MB exceeds spring.servlet.multipart.max-file-size=5MB
        byte[] oversized = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "huge.csv", "text/csv", oversized);

        mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value("Uploaded file is too large"));
    }
}
