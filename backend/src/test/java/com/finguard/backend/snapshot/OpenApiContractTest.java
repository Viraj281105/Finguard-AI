package com.finguard.backend.snapshot;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-03 Contract & Diff Gate Test:
 * <ul>
 *   <li>Verifies {@code contracts/openapi/finguard-api.v1.json} is in sync with live Spring controllers and DTOs.</li>
 *   <li>Verifies {@code frontend/src/shared/api/generated/openapi.ts} is in sync with the OpenAPI 3.1.0 spec.</li>
 *   <li>Cross-checks that 100% of endpoints in {@code docs/API.md} are present in the OpenAPI spec and vice versa.</li>
 *   <li>Exercises {@link OpenApiDiffGate} against deliberate breaking and non-breaking contract mutations.</li>
 *   <li>Verifies that >= 80% of frontend API feature modules bind directly to generated OpenAPI types.</li>
 * </ul>
 */
class OpenApiContractTest extends IntegrationTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path OPENAPI_SPEC_PATH = REPO_ROOT.resolve("contracts/openapi/finguard-api.v1.json");
    private static final Path GENERATED_TS_PATH = REPO_ROOT.resolve("frontend/src/shared/api/generated/openapi.ts");
    private static final Path API_MD_PATH = REPO_ROOT.resolve("docs/API.md");
    private static final Path FRONTEND_FEATURES_DIR = REPO_ROOT.resolve("frontend/src/features");

    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");

    // Matches headings like: ### `POST /api/auth/register` or ### `GET /api/forecast/cash-flow?horizonMonths=3`
    private static final Pattern HEADING_ENDPOINT = Pattern.compile(
            "^###\\s+`(GET|POST|PUT|PATCH|DELETE)\\s+(/api/[^`\\s?]+)(?:\\?[^`]*)?`",
            Pattern.MULTILINE
    );

    // Matches table rows like: | `POST /api/transactions` | or | `POST` | `/api/scenarios` |
    private static final Pattern TABLE_COMBINED_ENDPOINT = Pattern.compile(
            "^\\|\\s*`(GET|POST|PUT|PATCH|DELETE)\\s+(/api/[^`\\s?]+)`\\s*\\|",
            Pattern.MULTILINE
    );
    private static final Pattern TABLE_SPLIT_ENDPOINT = Pattern.compile(
            "^\\|\\s*`(GET|POST|PUT|PATCH|DELETE)`\\s*\\|\\s*`(/api/[^`\\s?]+)`\\s*\\|",
            Pattern.MULTILINE
    );

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("S1-03: OpenAPI 3.1 snapshot and generated TypeScript client match live controllers and DTOs")
    void openApiSnapshotMatchesLiveControllersAndGeneratedTypeScript() throws Exception {
        ObjectNode liveSpec = OpenApiSpecGenerator.generate(handlerMapping);
        String liveJson = OpenApiSpecGenerator.toJsonString(liveSpec);
        String liveTypeScript = OpenApiSpecGenerator.generateTypeScript(liveSpec);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(OPENAPI_SPEC_PATH.getParent());
            Files.writeString(OPENAPI_SPEC_PATH, liveJson, StandardCharsets.UTF_8);
            Files.createDirectories(GENERATED_TS_PATH.getParent());
            Files.writeString(GENERATED_TS_PATH, liveTypeScript, StandardCharsets.UTF_8);
        }

        assertThat(OPENAPI_SPEC_PATH)
                .as("Committed OpenAPI snapshot at %s (run with -Dopenapi.update=true to regenerate)", OPENAPI_SPEC_PATH)
                .exists();
        assertThat(GENERATED_TS_PATH)
                .as("Generated TypeScript definitions at %s (run with -Dopenapi.update=true to regenerate)", GENERATED_TS_PATH)
                .exists();

        String committedJson = Files.readString(OPENAPI_SPEC_PATH, StandardCharsets.UTF_8).replace("\r\n", "\n");
        JsonNode committedSpec = json.readTree(committedJson);

        OpenApiDiffGate.DiffResult diff = OpenApiDiffGate.compare(committedSpec, liveSpec);
        assertThat(diff.breakingChanges())
                .as("Breaking API changes detected between committed OpenAPI snapshot and live controllers")
                .isEmpty();

        assertThat(liveJson)
                .as("OpenAPI 3.1 snapshot drifted from live controllers/DTOs. Run: ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true")
                .isEqualTo(committedJson);

        String committedTs = Files.readString(GENERATED_TS_PATH, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(liveTypeScript)
                .as("Generated TypeScript definitions drifted from OpenAPI snapshot. Run: ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true")
                .isEqualTo(committedTs);

        // Security check: no secrets or bearer tokens in committed spec
        assertThat(committedJson)
                .doesNotContain("eyJhbGci")
                .doesNotContain("AI_INTERNAL_TOKEN")
                .doesNotContain("JWT_SECRET");
    }

    @Test
    @DisplayName("S1-03: OpenAPI snapshot covers 100% of endpoints documented in docs/API.md and vice versa")
    void openApiSnapshotCoversEveryDocumentedEndpointInApiMd() throws Exception {
        String apiMd = Files.readString(API_MD_PATH, StandardCharsets.UTF_8);
        Set<String> documentedEndpoints = extractDocumentedEndpoints(apiMd);

        ObjectNode liveSpec = OpenApiSpecGenerator.generate(handlerMapping);
        Set<String> openApiEndpoints = extractOpenApiEndpoints(liveSpec);

        assertThat(documentedEndpoints)
                .as("docs/API.md must document all 60 public/protected /api endpoints")
                .hasSize(60);
        assertThat(openApiEndpoints)
                .as("OpenAPI 3.1 specification must contain exactly the endpoints documented in docs/API.md")
                .isEqualTo(documentedEndpoints);
    }

    @Test
    @DisplayName("S1-03: OpenApiDiffGate catches deliberate breaking changes and permits additive changes")
    void diffGateRejectsDeliberateBreakingChangesAndAllowsAdditiveChanges() {
        ObjectNode baseline = OpenApiSpecGenerator.generate(handlerMapping);

        // 1. Deliberate breaking change: remove an endpoint path
        ObjectNode removedPathCandidate = baseline.deepCopy();
        ((ObjectNode) removedPathCandidate.path("paths")).remove("/api/recommendations");
        OpenApiDiffGate.DiffResult r1 = OpenApiDiffGate.compare(baseline, removedPathCandidate);
        assertThat(r1.hasBreakingChanges()).isTrue();
        assertThat(r1.breakingChanges())
                .anyMatch(b -> "PATH_REMOVED".equals(b.rule()) && "/api/recommendations".equals(b.location()));

        // 2. Deliberate breaking change: remove an HTTP method on an existing path
        ObjectNode removedOpCandidate = baseline.deepCopy();
        ((ObjectNode) removedOpCandidate.path("paths").path("/api/transactions/{id}")).remove("delete");
        OpenApiDiffGate.DiffResult r2 = OpenApiDiffGate.compare(baseline, removedOpCandidate);
        assertThat(r2.hasBreakingChanges()).isTrue();
        assertThat(r2.breakingChanges())
                .anyMatch(b -> "OPERATION_REMOVED".equals(b.rule()) && "DELETE /api/transactions/{id}".equals(b.location()));

        // 3. Deliberate breaking change: make an optional query parameter required
        ObjectNode tightenedParamCandidate = baseline.deepCopy();
        ArrayNode getTxParams = (ArrayNode) tightenedParamCandidate.path("paths")
                .path("/api/transactions").path("get").path("parameters");
        for (JsonNode p : getTxParams.values()) {
            if ("category".equals(p.path("name").asText())) {
                ((ObjectNode) p).put("required", true);
            }
        }
        OpenApiDiffGate.DiffResult r3 = OpenApiDiffGate.compare(baseline, tightenedParamCandidate);
        assertThat(r3.hasBreakingChanges()).isTrue();
        assertThat(r3.breakingChanges())
                .anyMatch(b -> "PARAMETER_MADE_REQUIRED".equals(b.rule())
                        && b.location().contains("GET /api/transactions (query:category)"));

        // 4. Deliberate breaking change: remove a response property from TransactionResponse
        ObjectNode removedRespPropCandidate = baseline.deepCopy();
        ((ObjectNode) removedRespPropCandidate.path("components").path("schemas")
                .path("TransactionResponse").path("properties")).remove("amount");
        OpenApiDiffGate.DiffResult r4 = OpenApiDiffGate.compare(baseline, removedRespPropCandidate);
        assertThat(r4.hasBreakingChanges()).isTrue();
        assertThat(r4.breakingChanges())
                .anyMatch(b -> "RESPONSE_PROPERTY_REMOVED".equals(b.rule())
                        && "components.schemas.TransactionResponse.amount".equals(b.location()));

        // 5. Deliberate breaking change: make a non-null response property nullable
        ObjectNode nullableRespPropCandidate = baseline.deepCopy();
        ObjectNode titleProp = (ObjectNode) nullableRespPropCandidate.path("components").path("schemas")
                .path("TransactionResponse").path("properties").path("title");
        ArrayNode nullableType = nullableRespPropCandidate.arrayNode();
        nullableType.add("string").add("null");
        titleProp.set("type", nullableType);
        OpenApiDiffGate.DiffResult r5 = OpenApiDiffGate.compare(baseline, nullableRespPropCandidate);
        assertThat(r5.hasBreakingChanges()).isTrue();
        assertThat(r5.breakingChanges())
                .anyMatch(b -> "RESPONSE_PROPERTY_MADE_NULLABLE".equals(b.rule())
                        && "components.schemas.TransactionResponse.title".equals(b.location()));

        // 6. Deliberate breaking change: make an optional request property required
        ObjectNode tightenedReqPropCandidate = baseline.deepCopy();
        ArrayNode reqRequired = (ArrayNode) tightenedReqPropCandidate.path("components").path("schemas")
                .path("TransactionRequest").path("required");
        reqRequired.add("note");
        OpenApiDiffGate.DiffResult r6 = OpenApiDiffGate.compare(baseline, tightenedReqPropCandidate);
        assertThat(r6.hasBreakingChanges()).isTrue();
        assertThat(r6.breakingChanges())
                .anyMatch(b -> "REQUEST_PROPERTY_MADE_REQUIRED".equals(b.rule())
                        && "components.schemas.TransactionRequest.note".equals(b.location()));

        // 7. Non-breaking additive change: add a new optional response field and optional query parameter
        ObjectNode additiveCandidate = baseline.deepCopy();
        ((ObjectNode) additiveCandidate.path("components").path("schemas")
                .path("TransactionResponse").path("properties"))
                .putObject("merchantNormalized").put("type", "string");
        OpenApiDiffGate.DiffResult r7 = OpenApiDiffGate.compare(baseline, additiveCandidate);
        assertThat(r7.hasBreakingChanges()).isFalse();
        assertThat(r7.nonBreakingChanges())
                .contains("Added response property components.schemas.TransactionResponse.merchantNormalized");
    }

    @Test
    @DisplayName("S1-03: >= 80% of frontend API feature modules bind directly to generated OpenAPI types")
    void frontendApiLayerBindsToGeneratedOpenApiTypes() throws Exception {
        List<Path> featureTypeFiles;
        try (Stream<Path> paths = Files.walk(FRONTEND_FEATURES_DIR, 2)) {
            featureTypeFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("types.ts"))
                    .sorted()
                    .toList();
        }

        assertThat(featureTypeFiles).isNotEmpty();

        long boundCount = 0;
        for (Path typeFile : featureTypeFiles) {
            String content = Files.readString(typeFile, StandardCharsets.UTF_8);
            if (content.contains("shared/api/generated/openapi")) {
                boundCount++;
            }
        }

        double coverageRatio = (double) boundCount / featureTypeFiles.size();
        assertThat(coverageRatio)
                .as("Proportion of frontend feature API type modules bound to generated/openapi.ts (%d / %d)",
                        boundCount, featureTypeFiles.size())
                .isGreaterThanOrEqualTo(0.80);
    }

    private static Set<String> extractDocumentedEndpoints(String markdown) {
        Set<String> endpoints = new TreeSet<>();
        Matcher m1 = HEADING_ENDPOINT.matcher(markdown);
        while (m1.find()) {
            endpoints.add(normalizeEndpoint(m1.group(1), m1.group(2)));
        }
        Matcher m2 = TABLE_COMBINED_ENDPOINT.matcher(markdown);
        while (m2.find()) {
            endpoints.add(normalizeEndpoint(m2.group(1), m2.group(2)));
        }
        Matcher m3 = TABLE_SPLIT_ENDPOINT.matcher(markdown);
        while (m3.find()) {
            endpoints.add(normalizeEndpoint(m3.group(1), m3.group(2)));
        }
        return endpoints;
    }

    private static Set<String> extractOpenApiEndpoints(JsonNode spec) {
        Set<String> endpoints = new TreeSet<>();
        JsonNode paths = spec.path("paths");
        for (Map.Entry<String, JsonNode> entry : paths.properties()) {
            String path = entry.getKey();
            JsonNode pathItem = entry.getValue();
            for (String method : HTTP_METHODS) {
                if (pathItem.has(method)) {
                    endpoints.add(normalizeEndpoint(method.toUpperCase(Locale.ROOT), path));
                }
            }
        }
        return endpoints;
    }

    private static String normalizeEndpoint(String method, String path) {
        // Normalize path parameter placeholder names (e.g. {goalId} -> {param}) for structural comparison
        String normalizedPath = path.replaceAll("\\{[^}/]+}", "{param}");
        return method.toUpperCase(Locale.ROOT) + " " + normalizedPath;
    }
}

