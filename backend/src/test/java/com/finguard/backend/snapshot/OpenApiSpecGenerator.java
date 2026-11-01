package com.finguard.backend.snapshot;

import com.finguard.backend.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates the canonical OpenAPI 3.1.0 specification ({@code contracts/openapi/finguard-api.v1.json})
 * directly from Spring Boot's live {@link RequestMappingHandlerMapping} and Java record/enum types,
 * and compiles the OpenAPI 3.1.0 JSON into TypeScript contract types
 * ({@code frontend/src/shared/api/generated/openapi.ts}).
 */
public final class OpenApiSpecGenerator {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final List<String> METHOD_ORDER = List.of("get", "post", "put", "patch", "delete");

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh"
    );

    private static final Set<String> CREATED_201_POST_PATHS = Set.of(
            "/api/budgets",
            "/api/goals",
            "/api/goals/{id}/contributions",
            "/api/investments",
            "/api/loans",
            "/api/scenarios",
            "/api/transactions"
    );

    /**
     * Explicit response-field nullability & optionality metadata where Java reference types
     * carry domain-specific nullability or string-enum semantics on the wire.
     * Validated at generation time against the actual Java {@link RecordComponent}s.
     */
    private static final Map<String, Set<String>> OPTIONAL_FIELDS = Map.ofEntries(
            Map.entry("ApiError", Set.of("fieldErrors")),
            Map.entry("AuthResponse", Set.of("refreshToken")),
            Map.entry("RefreshRequest", Set.of("refreshToken")),
            Map.entry("LogoutRequest", Set.of("refreshToken", "allDevices")),
            Map.entry("TransactionResponse", Set.of(
                    "categorySource", "categoryConfidence", "categoryConfidenceCalibrated",
                    "categoryNeedsReview", "categoryModelVersion", "categoryRule")),
            Map.entry("TransactionRequest", Set.of("date", "category", "note")),
            Map.entry("InvestmentRequest", Set.of("currentValue", "notes")),
            Map.entry("LoanRequest", Set.of("startDate", "endDate")),
            Map.entry("UploadStatusResponse", Set.of(
                    "rowErrors", "classificationStatus", "classifiedRows",
                    "closingBalance", "statementFrom", "statementTo")),
            Map.entry("CreateBudgetRequest", Set.of("category")),
            Map.entry("GoalResponseDto", Set.of("description", "targetDate", "daysRemaining", "requiredMonthlyPace")),
            Map.entry("GoalContributionDto", Set.of("note")),
            Map.entry("CreateGoalRequest", Set.of("description", "targetDate")),
            Map.entry("UpdateGoalRequest", Set.of("name", "description", "targetAmount", "targetDate", "status")),
            Map.entry("GoalContributionRequest", Set.of("contributionDate", "note")),
            Map.entry("ScenarioSimulationRequest", Set.of(
                    "name", "incomeAdjustment", "categoryAdjustments", "addedRecurringExpenses",
                    "removedRecurringExpenseIds", "loanAdjustments", "oneTimeItems")),
            Map.entry("ScenarioSimulationRequest_AddedRecurringExpense", Set.of("category")),
            Map.entry("ScenarioSimulationRequest_LoanAdjustment", Set.of("amount"))
    );

    private static final Map<String, Set<String>> NULLABLE_FIELDS = Map.ofEntries(
            Map.entry("TransactionResponse", Set.of(
                    "category", "note", "categorySource", "categoryConfidence",
                    "categoryModelVersion", "categoryRule")),
            Map.entry("InvestmentResponse", Set.of("currentValue", "notes")),
            Map.entry("LoanResponse", Set.of("startDate", "endDate")),
            Map.entry("UploadStatusResponse", Set.of(
                    "closingBalance", "statementFrom", "statementTo",
                    "errorMessage", "createdAt", "completedAt")),
            Map.entry("PeriodDto", Set.of("from", "to")),
            Map.entry("MomComparisonDto", Set.of("incomeChangePct", "expenseChangePct")),
            Map.entry("FinancialInsightDto", Set.of("confidenceCaveat")),
            Map.entry("FinancialSummaryResponse", Set.of("savingsRate", "savingsRateCaveat", "momComparison")),
            Map.entry("MonthlyFlowDto", Set.of("savingsRate", "momExpenseChangePct", "momIncomeChangePct")),
            Map.entry("TransactionBriefDto", Set.of("category")),
            Map.entry("CategorySpendingDto", Set.of("momChangePct", "largestTransaction")),
            Map.entry("CategoryBreakdownResponse", Set.of("largestCategory")),
            Map.entry("DataQualityResponse", Set.of("coverageFrom", "coverageTo")),
            Map.entry("BudgetDto", Set.of("category")),
            Map.entry("CreateBudgetRequest", Set.of("category")),
            Map.entry("MonthlyBudgetOverviewResponse", Set.of("overallBudget")),
            Map.entry("ForecastCommon_Estimate", Set.of("lower", "upper")),
            Map.entry("ForecastCommon_HistoryInfo", Set.of("from", "to")),
            Map.entry("CashFlowForecastResponse", Set.of("currentMonth", "summary")),
            Map.entry("CashFlowForecastResponse_ProjectedMonth", Set.of("income", "net")),
            Map.entry("CashFlowForecastResponse_Summary", Set.of(
                    "totalIncome", "totalNet", "averageMonthlyNet", "savingsRatePercent")),
            Map.entry("CashFlowForecastResponse_RecurringInfluence", Set.of("category")),
            Map.entry("CategorySpendingForecastResponse_CategoryForecast", Set.of("trendPercentPerMonth")),
            Map.entry("BudgetOverrunRiskResponse", Set.of("overall")),
            Map.entry("BudgetOverrunRiskResponse_Item", Set.of(
                    "category", "expectedLow", "expectedCentral", "expectedHigh",
                    "estimatedOverrun", "indeterminateReason")),
            Map.entry("SavingsProjectionResponse", Set.of("averageMonthlyNet", "projectedSavingsRatePercent")),
            Map.entry("SavingsProjectionResponse_GoalOutlook", Set.of(
                    "targetDate", "requiredMonthlyPace", "monthsIfAllSurplusUsed",
                    "completionMonthIfAllSurplusUsed", "onPaceIfAlone")),
            Map.entry("SavingsProjectionResponse_Accounting", Set.of(
                    "projectedSurplusOverHorizon", "verifiedBankBalance",
                    "totalRequiredGoalPace", "goalsOvercommitted")),
            Map.entry("ScenarioSimulationResponse", Set.of("unavailableReason", "baseline", "scenario", "delta")),
            Map.entry("ScenarioSimulationResponse_Totals", Set.of("income", "net", "savingsRatePercent")),
            Map.entry("ScenarioSimulationResponse_Delta", Set.of("income", "net", "savingsRatePoints")),
            Map.entry("ScenarioSimulationResponse_Month", Set.of(
                    "baselineIncome", "scenarioIncome", "baselineNet", "scenarioNet")),
            Map.entry("SavedScenarioResponses_Summary", Set.of("netChange", "spendingChange")),
            Map.entry("BacktestSummaryResponse_ErrorMetrics", Set.of("wapePercent")),
            Map.entry("RecommendationsResponse_Recommendation", Set.of("estimatedMonthlyImpact", "scenarioPreset"))
    );

    private static final Map<String, List<String>> STRING_ENUM_FIELDS = Map.ofEntries(
            Map.entry("FinancialInsightDto.type", List.of("ALERT", "TREND", "INFO", "POSITIVE")),
            Map.entry("RecurringExpenseItemDto.cadence", List.of("MONTHLY", "WEEKLY", "ANNUAL")),
            Map.entry("RecurringExpenseItemDto.confidence", List.of("HIGH", "MEDIUM", "LOW")),
            Map.entry("BudgetPerformanceSummaryDto.status", List.of("UNDER_BUDGET", "APPROACHING_LIMIT", "OVER_BUDGET")),
            Map.entry("CategoryBudgetPerformanceDto.status", List.of("UNDER_BUDGET", "APPROACHING_LIMIT", "OVER_BUDGET")),
            Map.entry("CashFlowForecastResponse.reliability", List.of("RELIABLE", "LIMITED", "UNAVAILABLE")),
            Map.entry("CategorySpendingForecastResponse.reliability", List.of("RELIABLE", "LIMITED", "UNAVAILABLE")),
            Map.entry("CategorySpendingForecastResponse_CategoryForecast.reliability", List.of("RELIABLE", "LIMITED", "UNAVAILABLE")),
            Map.entry("CategorySpendingForecastResponse_CategoryForecast.trend", List.of("UPWARD", "DOWNWARD", "STABLE", "NOT_ESTABLISHED")),
            Map.entry("BudgetOverrunRiskResponse.period", List.of("PAST", "CURRENT", "FUTURE")),
            Map.entry("BudgetOverrunRiskResponse_Item.risk", List.of(
                    "EXCEEDED", "LIKELY_OVER", "AT_RISK", "ON_TRACK",
                    "INDETERMINATE", "COMPLETED_WITHIN", "COMPLETED_OVER")),
            Map.entry("SavingsProjectionResponse.reliability", List.of("RELIABLE", "LIMITED", "UNAVAILABLE")),
            Map.entry("ScenarioSimulationResponse.baselineReliability", List.of("RELIABLE", "LIMITED", "UNAVAILABLE")),
            Map.entry("ScenarioSimulationResponse_LoanEffect.action", List.of("CLOSE", "PREPAY", "CHANGE_EMI")),
            Map.entry("BacktestSummaryResponse.status", List.of("COMPLETED", "INSUFFICIENT_DATA")),
            Map.entry("BacktestSummaryResponse.evaluatedOn", List.of("USER_HISTORY")),
            Map.entry("BacktestSummaryResponse_SeriesResult.series", List.of("EXPENSES", "INCOME", "NET")),
            Map.entry("BacktestSummaryResponse_HorizonResult.verdict", List.of("MODEL_BETTER", "SIMILAR", "BASELINE_BETTER", "INCONCLUSIVE"))
    );

    private OpenApiSpecGenerator() {
    }

    public static ObjectNode generate(RequestMappingHandlerMapping handlerMapping) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("openapi", "3.1.0");

        ObjectNode info = root.putObject("info");
        info.put("title", "FinGuard-AI HTTP API");
        info.put("version", "1.0.0");
        info.put("description", "Machine-verified OpenAPI 3.1.0 contract generated from Spring Boot controllers and DTO records.");

        ArrayNode servers = root.putArray("servers");
        ObjectNode devServer = servers.addObject();
        devServer.put("url", "http://localhost:8080");
        devServer.put("description", "Local development and staging server");

        Map<String, ObjectNode> discoveredSchemas = new TreeMap<>();
        registerSchema(ApiError.class, discoveredSchemas);

        Map<String, Map<String, HandlerEntry>> routeMap = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();

            Set<String> patterns = mappingInfo.getPatternValues();
            Set<RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();
            for (String path : patterns) {
                if (!path.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod reqMethod : methods) {
                    String httpMethod = reqMethod.name().toLowerCase(Locale.ROOT);
                    routeMap.computeIfAbsent(path, k -> new LinkedHashMap<>())
                            .put(httpMethod, new HandlerEntry(path, httpMethod, handlerMethod));
                }
            }
        }

        ObjectNode pathsNode = root.putObject("paths");
        for (Map.Entry<String, Map<String, HandlerEntry>> pathEntry : routeMap.entrySet()) {
            String path = pathEntry.getKey();
            ObjectNode pathItem = pathsNode.putObject(path);
            Map<String, HandlerEntry> methodMap = pathEntry.getValue();

            List<String> sortedMethods = new ArrayList<>(methodMap.keySet());
            sortedMethods.sort(Comparator.comparingInt(m -> {
                int idx = METHOD_ORDER.indexOf(m);
                return idx >= 0 ? idx : 99;
            }));

            for (String httpMethod : sortedMethods) {
                HandlerEntry handler = methodMap.get(httpMethod);
                pathItem.set(httpMethod, buildOperationNode(handler, discoveredSchemas));
            }
        }

        ObjectNode components = root.putObject("components");
        ObjectNode securitySchemes = components.putObject("securitySchemes");
        ObjectNode bearerAuth = securitySchemes.putObject("bearerAuth");
        bearerAuth.put("type", "http");
        bearerAuth.put("scheme", "bearer");
        bearerAuth.put("bearerFormat", "JWT");

        ObjectNode schemasNode = components.putObject("schemas");
        for (Map.Entry<String, ObjectNode> schemaEntry : discoveredSchemas.entrySet()) {
            schemasNode.set(schemaEntry.getKey(), schemaEntry.getValue());
        }

        validateMetadataAgainstDiscoveredSchemas(discoveredSchemas);
        return root;
    }

    public static String toJsonString(ObjectNode spec) {
        return MAPPER.writeValueAsString(spec).replace("\r\n", "\n") + "\n";
    }

    private record HandlerEntry(String path, String httpMethod, HandlerMethod handlerMethod) {
    }

    private static ObjectNode buildOperationNode(
            HandlerEntry entry,
            Map<String, ObjectNode> discoveredSchemas
    ) {
        ObjectNode op = MAPPER.createObjectNode();
        Method method = entry.handlerMethod().getMethod();
        Class<?> beanType = entry.handlerMethod().getBeanType();

        String controllerTag = deriveTag(entry.path());
        op.putArray("tags").add(controllerTag);

        String controllerPrefix = beanType.getSimpleName().replace("Controller", "");
        op.put("operationId", controllerPrefix + "_" + method.getName());
        op.put("summary", entry.httpMethod().toUpperCase(Locale.ROOT) + " " + entry.path());

        if (PUBLIC_PATHS.contains(entry.path())) {
            op.putArray("security");
        } else {
            ArrayNode sec = op.putArray("security");
            sec.addObject().putArray("bearerAuth");
        }

        ArrayNode parameters = MAPPER.createArrayNode();
        ObjectNode requestBodyNode = null;

        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(AuthenticationPrincipal.class)
                    || HttpServletRequest.class.isAssignableFrom(param.getType())
                    || HttpServletResponse.class.isAssignableFrom(param.getType())) {
                continue;
            }

            PathVariable pathVar = AnnotatedElementUtils.findMergedAnnotation(param, PathVariable.class);
            if (pathVar != null) {
                String name = !pathVar.value().isBlank() ? pathVar.value()
                        : (!pathVar.name().isBlank() ? pathVar.name() : param.getName());
                ObjectNode pNode = parameters.addObject();
                pNode.put("name", name);
                pNode.put("in", "path");
                pNode.put("required", true);
                pNode.set("schema", buildTypeSchema(param.getParameterizedType(), discoveredSchemas));
                continue;
            }

            RequestParam reqParam = AnnotatedElementUtils.findMergedAnnotation(param, RequestParam.class);
            if (reqParam != null) {
                String name = !reqParam.value().isBlank() ? reqParam.value()
                        : (!reqParam.name().isBlank() ? reqParam.name() : param.getName());
                if (MultipartFile.class.isAssignableFrom(param.getType())) {
                    requestBodyNode = MAPPER.createObjectNode();
                    requestBodyNode.put("required", true);
                    ObjectNode content = requestBodyNode.putObject("content");
                    ObjectNode multipart = content.putObject("multipart/form-data");
                    ObjectNode schema = multipart.putObject("schema");
                    schema.put("type", "object");
                    schema.putArray("required").add(name);
                    ObjectNode props = schema.putObject("properties");
                    ObjectNode fileProp = props.putObject(name);
                    fileProp.put("type", "string");
                    fileProp.put("format", "binary");
                } else {
                    boolean hasDefault = !ValueConstants.DEFAULT_NONE.equals(reqParam.defaultValue());
                    boolean required = reqParam.required() && !hasDefault;
                    ObjectNode pNode = parameters.addObject();
                    pNode.put("name", name);
                    pNode.put("in", "query");
                    pNode.put("required", required);
                    ObjectNode pSchema = buildTypeSchema(param.getParameterizedType(), discoveredSchemas);
                    if (hasDefault) {
                        putDefaultValue(pSchema, param.getType(), reqParam.defaultValue());
                    }
                    pNode.set("schema", pSchema);
                }
                continue;
            }

            CookieValue cookieVal = AnnotatedElementUtils.findMergedAnnotation(param, CookieValue.class);
            if (cookieVal != null) {
                String name = !cookieVal.value().isBlank() ? cookieVal.value()
                        : (!cookieVal.name().isBlank() ? cookieVal.name() : param.getName());
                ObjectNode pNode = parameters.addObject();
                pNode.put("name", name);
                pNode.put("in", "cookie");
                pNode.put("required", cookieVal.required());
                pNode.set("schema", buildTypeSchema(param.getParameterizedType(), discoveredSchemas));
                continue;
            }

            RequestBody reqBody = AnnotatedElementUtils.findMergedAnnotation(param, RequestBody.class);
            if (reqBody != null) {
                requestBodyNode = MAPPER.createObjectNode();
                requestBodyNode.put("required", reqBody.required());
                ObjectNode content = requestBodyNode.putObject("content");
                ObjectNode appJson = content.putObject("application/json");
                appJson.set("schema", buildTypeSchema(param.getParameterizedType(), discoveredSchemas));
            }
        }

        if (!parameters.isEmpty()) {
            op.set("parameters", parameters);
        }
        if (requestBodyNode != null) {
            op.set("requestBody", requestBodyNode);
        }

        ObjectNode responses = op.putObject("responses");
        Type returnbodyType = unwrapResponseEntity(method.getGenericReturnType());
        String successStatus = determineSuccessStatus(entry.path(), entry.httpMethod(), returnbodyType);
        ObjectNode successNode = responses.putObject(successStatus);
        successNode.put("description", successDescription(successStatus));

        if ("/api/transactions".equals(entry.path()) && "get".equals(entry.httpMethod())) {
            ObjectNode headers = successNode.putObject("headers");
            headers.putObject("X-Total-Count").put("description", "Total matching transactions")
                    .putObject("schema").put("type", "string");
            headers.putObject("X-Page").put("description", "0-based page index")
                    .putObject("schema").put("type", "string");
            headers.putObject("X-Page-Size").put("description", "Maximum entries per page")
                    .putObject("schema").put("type", "string");
            headers.putObject("X-Total-Pages").put("description", "Total pages available")
                    .putObject("schema").put("type", "string");
        } else if (entry.path().startsWith("/api/auth/")) {
            ObjectNode headers = successNode.putObject("headers");
            headers.putObject("Set-Cookie").put("description", "HttpOnly refreshToken cookie")
                    .putObject("schema").put("type", "string");
        }

        if (returnbodyType != Void.class && returnbodyType != void.class) {
            ObjectNode content = successNode.putObject("content");
            ObjectNode appJson = content.putObject("application/json");
            appJson.set("schema", buildTypeSchema(returnbodyType, discoveredSchemas));
        }

        addErrorResponse(responses, "400", "Validation failure or invalid request parameter");
        addErrorResponse(responses, "401", "Authentication required or invalid credentials");
        if (!PUBLIC_PATHS.contains(entry.path())) {
            addErrorResponse(responses, "404", "Resource not found or owned by another user");
        }
        if ("post".equals(entry.httpMethod())) {
            addErrorResponse(responses, "409", "Conflict with existing resource state");
        }
        if (entry.path().startsWith("/api/auth/")) {
            addErrorResponse(responses, "429", "Rate limit exceeded");
        }

        return op;
    }

    private static void addErrorResponse(ObjectNode responses, String status, String description) {
        ObjectNode err = responses.putObject(status);
        err.put("description", description);
        err.putObject("content")
                .putObject("application/json")
                .putObject("schema")
                .put("$ref", "#/components/schemas/ApiError");
    }

    private static String deriveTag(String path) {
        String[] parts = path.split("/");
        return parts.length >= 3 ? parts[2] : "api";
    }

    private static Type unwrapResponseEntity(Type returnType) {
        if (returnType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            if (org.springframework.http.ResponseEntity.class.isAssignableFrom(raw)) {
                return pt.getActualTypeArguments()[0];
            }
        }
        return returnType;
    }

    private static String determineSuccessStatus(String path, String httpMethod, Type bodyType) {
        if (bodyType == Void.class || bodyType == void.class) {
            return "204";
        }
        if ("/api/upload/csv".equals(path) && "post".equals(httpMethod)) {
            return "202";
        }
        if ("post".equals(httpMethod) && CREATED_201_POST_PATHS.contains(path)) {
            return "201";
        }
        return "200";
    }

    private static String successDescription(String status) {
        return switch (status) {
            case "201" -> "Created";
            case "202" -> "Accepted for asynchronous processing";
            case "204" -> "No Content";
            default -> "OK";
        };
    }

    private static void putDefaultValue(ObjectNode schema, Class<?> paramType, String defaultValue) {
        if (paramType == int.class || paramType == Integer.class || paramType == long.class || paramType == Long.class) {
            schema.put("default", Integer.parseInt(defaultValue));
        } else if (paramType == boolean.class || paramType == Boolean.class) {
            schema.put("default", Boolean.parseBoolean(defaultValue));
        } else {
            schema.put("default", defaultValue);
        }
    }

    private static ObjectNode buildTypeSchema(Type type, Map<String, ObjectNode> discoveredSchemas) {
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawClass) {
                if (List.class.isAssignableFrom(rawClass) || Set.class.isAssignableFrom(rawClass)) {
                    ObjectNode arr = MAPPER.createObjectNode();
                    arr.put("type", "array");
                    arr.set("items", buildTypeSchema(pt.getActualTypeArguments()[0], discoveredSchemas));
                    return arr;
                }
                if (Map.class.isAssignableFrom(rawClass)) {
                    ObjectNode mapNode = MAPPER.createObjectNode();
                    mapNode.put("type", "object");
                    Type valType = pt.getActualTypeArguments()[1];
                    if (valType == Object.class) {
                        mapNode.put("additionalProperties", true);
                    } else {
                        mapNode.set("additionalProperties", buildTypeSchema(valType, discoveredSchemas));
                    }
                    return mapNode;
                }
            }
        }

        if (type instanceof Class<?> cls) {
            if (cls == String.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "string");
                return s;
            }
            if (cls == int.class || cls == Integer.class || cls == long.class || cls == Long.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "integer");
                s.put("format", (cls == long.class || cls == Long.class) ? "int64" : "int32");
                return s;
            }
            if (cls == BigDecimal.class || cls == double.class || cls == Double.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "number");
                return s;
            }
            if (cls == boolean.class || cls == Boolean.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "boolean");
                return s;
            }
            if (cls == LocalDate.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "string");
                s.put("format", "date");
                return s;
            }
            if (cls == LocalDateTime.class || cls == Instant.class) {
                ObjectNode s = MAPPER.createObjectNode();
                s.put("type", "string");
                s.put("format", "date-time");
                return s;
            }
            if (cls.isEnum() || cls.isRecord()) {
                String refName = registerSchema(cls, discoveredSchemas);
                ObjectNode ref = MAPPER.createObjectNode();
                ref.put("$ref", "#/components/schemas/" + refName);
                return ref;
            }
        }

        ObjectNode fallback = MAPPER.createObjectNode();
        fallback.put("type", "object");
        return fallback;
    }

    public static String schemaNameFor(Class<?> cls) {
        Class<?> enclosing = cls.getEnclosingClass();
        if (enclosing == null) {
            return cls.getSimpleName();
        }
        if (cls.isEnum() && (enclosing.getSimpleName().equals("Transaction")
                || enclosing.getSimpleName().equals("Investment")
                || enclosing.getSimpleName().equals("Loan")
                || enclosing.getSimpleName().equals("UploadJob"))) {
            return cls.getSimpleName();
        }
        return enclosing.getSimpleName() + "_" + cls.getSimpleName();
    }

    private static String registerSchema(Class<?> cls, Map<String, ObjectNode> discoveredSchemas) {
        String name = schemaNameFor(cls);
        if (discoveredSchemas.containsKey(name)) {
            return name;
        }
        // Placeholder to break potential cycles
        ObjectNode schemaNode = MAPPER.createObjectNode();
        discoveredSchemas.put(name, schemaNode);

        if (cls.isEnum()) {
            schemaNode.put("type", "string");
            ArrayNode enumValues = schemaNode.putArray("enum");
            for (Object constant : cls.getEnumConstants()) {
                enumValues.add(((Enum<?>) constant).name());
            }
            return name;
        }

        if (cls.isRecord()) {
            schemaNode.put("type", "object");
            Set<String> optionalForRecord = OPTIONAL_FIELDS.getOrDefault(name, Set.of());
            Set<String> nullableForRecord = NULLABLE_FIELDS.getOrDefault(name, Set.of());

            List<String> requiredList = new ArrayList<>();
            ObjectNode propsNode = MAPPER.createObjectNode();

            for (RecordComponent rc : cls.getRecordComponents()) {
                String fieldName = rc.getName();
                boolean isOptional = optionalForRecord.contains(fieldName);
                boolean isNullable = nullableForRecord.contains(fieldName);

                if (!isOptional) {
                    requiredList.add(fieldName);
                }

                ObjectNode propSchema = buildPropertySchema(name, rc, isNullable, discoveredSchemas);
                propsNode.set(fieldName, propSchema);
            }

            if (!requiredList.isEmpty()) {
                ArrayNode reqArr = schemaNode.putArray("required");
                for (String reqField : requiredList) {
                    reqArr.add(reqField);
                }
            }
            schemaNode.set("properties", propsNode);
            schemaNode.put("additionalProperties", false);
        }

        return name;
    }

    private static ObjectNode buildPropertySchema(
            String recordSchemaName,
            RecordComponent rc,
            boolean isNullable,
            Map<String, ObjectNode> discoveredSchemas
    ) {
        String fieldName = rc.getName();
        String qualifiedField = recordSchemaName + "." + fieldName;

        // Special domain constants & structured sub-objects
        if ("ScenarioSimulationResponse.hypothetical".equals(qualifiedField)) {
            ObjectNode constNode = MAPPER.createObjectNode();
            constNode.put("type", "boolean");
            constNode.put("const", true);
            return constNode;
        }
        if ("SavingsProjectionResponse_Accounting.verifiedBankBalance".equals(qualifiedField)) {
            ObjectNode nullNode = MAPPER.createObjectNode();
            nullNode.put("type", "null");
            return nullNode;
        }
        if ("RecommendationsResponse_ScenarioPreset.request".equals(qualifiedField)) {
            ObjectNode presetReq = MAPPER.createObjectNode();
            presetReq.put("type", "object");
            ObjectNode presetProps = presetReq.putObject("properties");
            ObjectNode hm = presetProps.putObject("horizonMonths");
            hm.put("type", "integer");
            hm.put("format", "int32");
            ObjectNode catAdj = presetProps.putObject("categoryAdjustments");
            catAdj.put("type", "array");
            catAdj.putObject("items").put("$ref", "#/components/schemas/ScenarioSimulationRequest_CategoryAdjustment");
            presetReq.put("additionalProperties", false);
            return presetReq;
        }

        ObjectNode baseSchema;
        if (STRING_ENUM_FIELDS.containsKey(qualifiedField)) {
            baseSchema = MAPPER.createObjectNode();
            baseSchema.put("type", "string");
            ArrayNode enumArr = baseSchema.putArray("enum");
            for (String val : STRING_ENUM_FIELDS.get(qualifiedField)) {
                enumArr.add(val);
            }
        } else {
            baseSchema = buildTypeSchema(rc.getGenericType(), discoveredSchemas);
        }

        applyValidationAnnotations(rc, baseSchema);

        if (!isNullable) {
            return baseSchema;
        }

        if (baseSchema.has("$ref") || baseSchema.has("enum")) {
            ObjectNode anyOfWrapper = MAPPER.createObjectNode();
            ArrayNode anyOf = anyOfWrapper.putArray("anyOf");
            anyOf.add(baseSchema);
            anyOf.addObject().put("type", "null");
            return anyOfWrapper;
        }

        String primaryType = baseSchema.path("type").asText();
        ArrayNode typeArr = MAPPER.createArrayNode();
        typeArr.add(primaryType);
        typeArr.add("null");
        baseSchema.set("type", typeArr);
        return baseSchema;
    }

    private static void applyValidationAnnotations(RecordComponent rc, ObjectNode schema) {
        if (schema.has("$ref")) {
            return;
        }
        NotBlank notBlank = rc.getAnnotation(NotBlank.class);
        if (notBlank != null && "string".equals(schema.path("type").asText())) {
            schema.put("minLength", 1);
        }
        Size size = rc.getAnnotation(Size.class);
        if (size != null) {
            String t = schema.path("type").asText();
            if ("string".equals(t)) {
                if (size.min() > 0) {
                    schema.put("minLength", size.min());
                }
                if (size.max() < Integer.MAX_VALUE) {
                    schema.put("maxLength", size.max());
                }
            } else if ("array".equals(t)) {
                if (size.max() < Integer.MAX_VALUE) {
                    schema.put("maxItems", size.max());
                }
            }
        }
        Pattern pattern = rc.getAnnotation(Pattern.class);
        if (pattern != null) {
            schema.put("pattern", pattern.regexp());
        }
        Email email = rc.getAnnotation(Email.class);
        if (email != null) {
            schema.put("format", "email");
        }
        Min min = rc.getAnnotation(Min.class);
        if (min != null) {
            schema.put("minimum", min.value());
        }
        Max max = rc.getAnnotation(Max.class);
        if (max != null) {
            schema.put("maximum", max.value());
        }
        Positive positive = rc.getAnnotation(Positive.class);
        if (positive != null) {
            schema.put("exclusiveMinimum", 0);
        }
        PositiveOrZero posOrZero = rc.getAnnotation(PositiveOrZero.class);
        if (posOrZero != null) {
            schema.put("minimum", 0);
        }
        DecimalMin decMin = rc.getAnnotation(DecimalMin.class);
        if (decMin != null) {
            schema.put("minimum", new BigDecimal(decMin.value()));
        }
        DecimalMax decMax = rc.getAnnotation(DecimalMax.class);
        if (decMax != null) {
            schema.put("maximum", new BigDecimal(decMax.value()));
        }
    }

    private static void validateMetadataAgainstDiscoveredSchemas(Map<String, ObjectNode> discoveredSchemas) {
        for (Map.Entry<String, Set<String>> entry : OPTIONAL_FIELDS.entrySet()) {
            ObjectNode schema = discoveredSchemas.get(entry.getKey());
            if (schema == null) {
                throw new IllegalStateException("OPTIONAL_FIELDS references unknown schema: " + entry.getKey());
            }
            JsonNode props = schema.path("properties");
            for (String f : entry.getValue()) {
                if (props.get(f) == null) {
                    throw new IllegalStateException("OPTIONAL_FIELDS references non-existent field: " + entry.getKey() + "." + f);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : NULLABLE_FIELDS.entrySet()) {
            ObjectNode schema = discoveredSchemas.get(entry.getKey());
            if (schema == null) {
                throw new IllegalStateException("NULLABLE_FIELDS references unknown schema: " + entry.getKey());
            }
            JsonNode props = schema.path("properties");
            for (String f : entry.getValue()) {
                if (props.get(f) == null) {
                    throw new IllegalStateException("NULLABLE_FIELDS references non-existent field: " + entry.getKey() + "." + f);
                }
            }
        }
        for (String qualified : STRING_ENUM_FIELDS.keySet()) {
            String[] parts = qualified.split("\\.");
            ObjectNode schema = discoveredSchemas.get(parts[0]);
            if (schema == null || schema.path("properties").get(parts[1]) == null) {
                throw new IllegalStateException("STRING_ENUM_FIELDS references non-existent field: " + qualified);
            }
        }
    }

    /**
     * Deterministically compiles the OpenAPI 3.1.0 specification JSON into TypeScript types
     * for {@code frontend/src/shared/api/generated/openapi.ts}.
     */
    public static String generateTypeScript(JsonNode openApiSpec) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("/**\n");
        sb.append(" * AUTO-GENERATED FILE — DO NOT EDIT MANUALLY.\n");
        sb.append(" * Source of truth: contracts/openapi/finguard-api.v1.json\n");
        sb.append(" * Regenerate via: ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true\n");
        sb.append(" */\n\n");

        JsonNode schemas = openApiSpec.path("components").path("schemas");
        sb.append("export interface components {\n");
        sb.append("  schemas: {\n");
        for (Map.Entry<String, JsonNode> entry : schemas.properties()) {
            String schemaName = entry.getKey();
            JsonNode schema = entry.getValue();
            sb.append("    ").append(schemaName).append(": ").append(renderTsType(schema, 4)).append(";\n");
        }
        sb.append("  };\n");
        sb.append("}\n\n");

        // Export convenient top-level type aliases for every component schema
        for (Map.Entry<String, JsonNode> entry : schemas.properties()) {
            String schemaName = entry.getKey();
            sb.append("export type ").append(schemaName)
                    .append(" = components[\"schemas\"][\"").append(schemaName).append("\"];\n");
        }
        sb.append("\n");

        // Export operations interface
        JsonNode paths = openApiSpec.path("paths");
        sb.append("export interface operations {\n");
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            JsonNode pathItem = pathEntry.getValue();
            for (String method : METHOD_ORDER) {
                JsonNode op = pathItem.get(method);
                if (op == null) {
                    continue;
                }
                String opId = op.path("operationId").asText();
                sb.append("  ").append(opId).append(": {\n");
                renderTsOperationBody(op, sb);
                sb.append("  };\n");
            }
        }
        sb.append("}\n\n");

        // Export paths interface
        sb.append("export interface paths {\n");
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            sb.append("  \"").append(path).append("\": {\n");
            for (String method : METHOD_ORDER) {
                JsonNode op = pathItem.get(method);
                if (op == null) {
                    continue;
                }
                String opId = op.path("operationId").asText();
                sb.append("    ").append(method).append(": operations[\"").append(opId).append("\"];\n");
            }
            sb.append("  };\n");
        }
        sb.append("}\n");

        return sb.toString();
    }

    private static void renderTsOperationBody(JsonNode op, StringBuilder sb) {
        JsonNode params = op.get("parameters");
        if (params != null && params.isArray() && !params.isEmpty()) {
            Map<String, List<JsonNode>> byIn = new LinkedHashMap<>();
            for (JsonNode p : params.values()) {
                byIn.computeIfAbsent(p.path("in").asText(), k -> new ArrayList<>()).add(p);
            }
            sb.append("    parameters: {\n");
            for (Map.Entry<String, List<JsonNode>> inEntry : byIn.entrySet()) {
                sb.append("      ").append(inEntry.getKey()).append(": {\n");
                for (JsonNode p : inEntry.getValue()) {
                    String pName = p.path("name").asText();
                    boolean req = p.path("required").asBoolean(false);
                    String tsType = renderTsType(p.path("schema"), 8);
                    sb.append("        ").append(pName).append(req ? ": " : "?: ").append(tsType).append(";\n");
                }
                sb.append("      };\n");
            }
            sb.append("    };\n");
        }

        JsonNode reqBody = op.get("requestBody");
        if (reqBody != null) {
            boolean req = reqBody.path("required").asBoolean(false);
            JsonNode jsonSchema = reqBody.path("content").path("application/json").get("schema");
            JsonNode multipartSchema = reqBody.path("content").path("multipart/form-data").get("schema");
            if (jsonSchema != null) {
                sb.append("    requestBody").append(req ? ": " : "?: ").append(renderTsType(jsonSchema, 4)).append(";\n");
            } else if (multipartSchema != null) {
                sb.append("    requestBody: FormData;\n");
            }
        }

        JsonNode responses = op.path("responses");
        sb.append("    responses: {\n");
        for (Map.Entry<String, JsonNode> respEntry : responses.properties()) {
            String status = respEntry.getKey();
            JsonNode resp = respEntry.getValue();
            JsonNode schema = resp.path("content").path("application/json").get("schema");
            if (schema != null) {
                sb.append("      ").append(status).append(": ").append(renderTsType(schema, 6)).append(";\n");
            } else {
                sb.append("      ").append(status).append(": void;\n");
            }
        }
        sb.append("    };\n");
    }

    private static String renderTsType(JsonNode schema, int indent) {
        if (schema == null || schema.isMissingNode()) {
            return "unknown";
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            String prefix = "#/components/schemas/";
            if (ref.startsWith(prefix)) {
                return "components[\"schemas\"][\"" + ref.substring(prefix.length()) + "\"]";
            }
            return "unknown";
        }
        if (schema.has("const")) {
            JsonNode c = schema.get("const");
            return c.isBoolean() ? String.valueOf(c.asBoolean()) : "\"" + c.asText() + "\"";
        }
        if (schema.has("anyOf")) {
            List<String> branches = new ArrayList<>();
            for (JsonNode b : schema.get("anyOf").values()) {
                branches.add(renderTsType(b, indent));
            }
            return String.join(" | ", branches);
        }
        if (schema.has("enum")) {
            List<String> literals = new ArrayList<>();
            for (JsonNode val : schema.get("enum").values()) {
                literals.add("\"" + val.asText() + "\"");
            }
            return String.join(" | ", literals);
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isArray()) {
            List<String> types = new ArrayList<>();
            for (JsonNode t : typeNode.values()) {
                types.add(renderSingleTsType(t.asText(), schema, indent));
            }
            return String.join(" | ", types);
        }

        String primaryType = typeNode != null ? typeNode.asText() : "object";
        return renderSingleTsType(primaryType, schema, indent);
    }

    private static String renderSingleTsType(String type, JsonNode schema, int indent) {
        return switch (type) {
            case "string" -> "string";
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "null";
            case "array" -> {
                String itemTs = renderTsType(schema.path("items"), indent);
                yield (itemTs.contains(" | ") ? "(" + itemTs + ")[]" : itemTs + "[]");
            }
            case "object" -> renderObjectTsType(schema, indent);
            default -> "unknown";
        };
    }

    private static String renderObjectTsType(JsonNode schema, int indent) {
        JsonNode props = schema.get("properties");
        if (props == null || !props.isObject()) {
            JsonNode addProps = schema.get("additionalProperties");
            if (addProps != null && addProps.isObject()) {
                return "Record<string, " + renderTsType(addProps, indent) + ">";
            }
            return "Record<string, unknown>";
        }

        Set<String> reqSet = new LinkedHashSet<>();
        JsonNode reqNode = schema.get("required");
        if (reqNode != null && reqNode.isArray()) {
            for (JsonNode r : reqNode.values()) {
                reqSet.add(r.asText());
            }
        }

        String pad = " ".repeat(indent);
        String innerPad = " ".repeat(indent + 2);
        StringBuilder sb = new StringBuilder("{\n");
        for (Map.Entry<String, JsonNode> propEntry : props.properties()) {
            String propName = propEntry.getKey();
            JsonNode propSchema = propEntry.getValue();
            boolean isReq = reqSet.contains(propName);
            sb.append(innerPad)
                    .append(propName)
                    .append(isReq ? ": " : "?: ")
                    .append(renderTsType(propSchema, indent + 2))
                    .append(";\n");
        }
        sb.append(pad).append("}");
        return sb.toString();
    }
}

