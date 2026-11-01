package com.finguard.backend.snapshot;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automated OpenAPI 3.1 breaking-change detector (in-process {@code oasdiff} gate for S1-03).
 *
 * <p>Detects backward-incompatible changes between the committed baseline OpenAPI specification
 * ({@code contracts/openapi/finguard-api.v1.json}) and a candidate specification:
 * <ul>
 *   <li>Removed API paths or HTTP methods</li>
 *   <li>Newly required query/path/cookie/header parameters or tightened parameter types/enums</li>
 *   <li>Optional request bodies becoming required, or request schemas adding required fields / removing optional fields / narrowing enums</li>
 *   <li>Removed success response status codes</li>
 *   <li>Response schemas removing properties, demoting required properties to optional, widening non-null response fields to nullable, or changing field types</li>
 * </ul>
 */
public final class OpenApiDiffGate {

    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");

    private OpenApiDiffGate() {
    }

    public record BreakingChange(String rule, String location, String detail) {
        @Override
        public String toString() {
            return "[" + rule + "] " + location + ": " + detail;
        }
    }

    public record DiffResult(List<BreakingChange> breakingChanges, List<String> nonBreakingChanges) {
        public boolean hasBreakingChanges() {
            return !breakingChanges.isEmpty();
        }
    }

    public static DiffResult compare(JsonNode baseline, JsonNode candidate) {
        List<BreakingChange> breaking = new ArrayList<>();
        List<String> nonBreaking = new ArrayList<>();

        JsonNode basePaths = baseline.path("paths");
        JsonNode candPaths = candidate.path("paths");
        JsonNode baseSchemas = baseline.path("components").path("schemas");
        JsonNode candSchemas = candidate.path("components").path("schemas");

        Set<String> requestSchemas = new HashSet<>();
        Set<String> responseSchemas = new HashSet<>();

        for (Map.Entry<String, JsonNode> pathEntry : propertiesOf(basePaths)) {
            String path = pathEntry.getKey();
            JsonNode basePathItem = pathEntry.getValue();
            JsonNode candPathItem = candPaths.get(path);

            if (candPathItem == null || candPathItem.isMissingNode()) {
                breaking.add(new BreakingChange(
                        "PATH_REMOVED",
                        path,
                        "Endpoint path was removed from the OpenAPI contract"));
                continue;
            }

            for (String method : HTTP_METHODS) {
                JsonNode baseOp = basePathItem.get(method);
                if (baseOp == null) {
                    if (candPathItem.get(method) != null) {
                        nonBreaking.add("Added operation " + method.toUpperCase() + " " + path);
                    }
                    continue;
                }
                String opLoc = method.toUpperCase() + " " + path;
                JsonNode candOp = candPathItem.get(method);
                if (candOp == null) {
                    breaking.add(new BreakingChange(
                            "OPERATION_REMOVED",
                            opLoc,
                            "HTTP operation was removed"));
                    continue;
                }

                compareParameters(opLoc, baseOp.path("parameters"), candOp.path("parameters"), breaking, nonBreaking);
                compareRequestBody(opLoc, baseOp.get("requestBody"), candOp.get("requestBody"),
                        baseSchemas, requestSchemas, breaking, nonBreaking);
                compareResponses(opLoc, baseOp.path("responses"), candOp.path("responses"),
                        baseSchemas, responseSchemas, breaking, nonBreaking);
            }
        }

        for (Map.Entry<String, JsonNode> candEntry : propertiesOf(candPaths)) {
            if (basePaths.get(candEntry.getKey()) == null) {
                nonBreaking.add("Added path " + candEntry.getKey());
            }
        }

        // Expand transitive schema references for request and response schemas
        Set<String> allRequestSchemas = expandTransitiveRefs(requestSchemas, baseSchemas);
        Set<String> allResponseSchemas = expandTransitiveRefs(responseSchemas, baseSchemas);

        for (String schemaName : allRequestSchemas) {
            JsonNode baseSchema = baseSchemas.get(schemaName);
            JsonNode candSchema = candSchemas.get(schemaName);
            if (baseSchema != null && candSchema != null) {
                compareRequestSchema("components.schemas." + schemaName, baseSchema, candSchema, breaking, nonBreaking);
            }
        }

        for (String schemaName : allResponseSchemas) {
            JsonNode baseSchema = baseSchemas.get(schemaName);
            JsonNode candSchema = candSchemas.get(schemaName);
            if (baseSchema != null && candSchema != null) {
                compareResponseSchema("components.schemas." + schemaName, baseSchema, candSchema, breaking, nonBreaking);
            } else if (baseSchema != null) {
                breaking.add(new BreakingChange(
                        "RESPONSE_SCHEMA_REMOVED",
                        "components.schemas." + schemaName,
                        "Referenced response schema was removed"));
            }
        }

        return new DiffResult(List.copyOf(breaking), List.copyOf(nonBreaking));
    }

    private static void compareParameters(
            String opLoc,
            JsonNode baseParams,
            JsonNode candParams,
            List<BreakingChange> breaking,
            List<String> nonBreaking
    ) {
        for (JsonNode candParam : elementsOf(candParams)) {
            String name = candParam.path("name").asText();
            String in = candParam.path("in").asText();
            boolean candReq = candParam.path("required").asBoolean(false);
            JsonNode baseParam = findParameter(baseParams, name, in);
            if (baseParam == null) {
                if (candReq) {
                    breaking.add(new BreakingChange(
                            "REQUIRED_PARAMETER_ADDED",
                            opLoc + " (" + in + ":" + name + ")",
                            "New required parameter added to existing operation"));
                } else {
                    nonBreaking.add("Added optional parameter " + name + " to " + opLoc);
                }
            } else {
                boolean baseReq = baseParam.path("required").asBoolean(false);
                if (!baseReq && candReq) {
                    breaking.add(new BreakingChange(
                            "PARAMETER_MADE_REQUIRED",
                            opLoc + " (" + in + ":" + name + ")",
                            "Optional parameter became required"));
                }
                String baseType = primaryType(baseParam.path("schema"));
                String candType = primaryType(candParam.path("schema"));
                if (!baseType.equals(candType)) {
                    breaking.add(new BreakingChange(
                            "PARAMETER_TYPE_CHANGED",
                            opLoc + " (" + in + ":" + name + ")",
                            "Parameter type changed from '" + baseType + "' to '" + candType + "'"));
                }
            }
        }
    }

    private static void compareRequestBody(
            String opLoc,
            JsonNode baseReqBody,
            JsonNode candReqBody,
            JsonNode baseSchemas,
            Set<String> requestSchemas,
            List<BreakingChange> breaking,
            List<String> nonBreaking
    ) {
        if (baseReqBody != null) {
            collectRefNames(baseReqBody, requestSchemas);
        }
        if (baseReqBody == null && candReqBody != null) {
            if (candReqBody.path("required").asBoolean(false)) {
                breaking.add(new BreakingChange(
                        "REQUIRED_REQUEST_BODY_ADDED",
                        opLoc,
                        "Operation that previously had no requestBody now requires one"));
            } else {
                nonBreaking.add("Added optional requestBody to " + opLoc);
            }
            return;
        }
        if (baseReqBody != null && candReqBody != null) {
            boolean baseReq = baseReqBody.path("required").asBoolean(false);
            boolean candReq = candReqBody.path("required").asBoolean(false);
            if (!baseReq && candReq) {
                breaking.add(new BreakingChange(
                        "REQUEST_BODY_MADE_REQUIRED",
                        opLoc,
                        "Optional requestBody became required"));
            }
        }
    }

    private static void compareResponses(
            String opLoc,
            JsonNode baseResponses,
            JsonNode candResponses,
            JsonNode baseSchemas,
            Set<String> responseSchemas,
            List<BreakingChange> breaking,
            List<String> nonBreaking
    ) {
        for (Map.Entry<String, JsonNode> entry : propertiesOf(baseResponses)) {
            String status = entry.getKey();
            JsonNode baseResp = entry.getValue();
            if (status.startsWith("2")) {
                collectRefNames(baseResp, responseSchemas);
            }
            JsonNode candResp = candResponses.get(status);
            if (candResp == null) {
                if (status.startsWith("2")) {
                    breaking.add(new BreakingChange(
                            "SUCCESS_RESPONSE_REMOVED",
                            opLoc + " -> " + status,
                            "Success HTTP status code " + status + " was removed"));
                }
            }
        }
    }

    private static void compareRequestSchema(
            String schemaLoc,
            JsonNode baseSchema,
            JsonNode candSchema,
            List<BreakingChange> breaking,
            List<String> nonBreaking
    ) {
        // Check enum narrowing in request schemas
        if (baseSchema.has("enum") && candSchema.has("enum")) {
            Set<String> baseEnums = toStringSet(baseSchema.get("enum"));
            Set<String> candEnums = toStringSet(candSchema.get("enum"));
            for (String val : baseEnums) {
                if (!candEnums.contains(val)) {
                    breaking.add(new BreakingChange(
                            "REQUEST_ENUM_VALUE_REMOVED",
                            schemaLoc,
                            "Allowed request enum value '" + val + "' was removed"));
                }
            }
        }

        Set<String> baseReqProps = toStringSet(baseSchema.get("required"));
        Set<String> candReqProps = toStringSet(candSchema.get("required"));
        for (String reqProp : candReqProps) {
            if (!baseReqProps.contains(reqProp)) {
                breaking.add(new BreakingChange(
                        "REQUEST_PROPERTY_MADE_REQUIRED",
                        schemaLoc + "." + reqProp,
                        "Request property '" + reqProp + "' became required"));
            }
        }

        JsonNode baseProps = baseSchema.path("properties");
        JsonNode candProps = candSchema.path("properties");
        for (Map.Entry<String, JsonNode> propEntry : propertiesOf(baseProps)) {
            String propName = propEntry.getKey();
            JsonNode baseProp = propEntry.getValue();
            JsonNode candProp = candProps.get(propName);
            if (candProp == null) {
                breaking.add(new BreakingChange(
                        "REQUEST_PROPERTY_REMOVED",
                        schemaLoc + "." + propName,
                        "Accepted request property '" + propName + "' was removed"));
            } else {
                String baseSignature = typeSignature(baseProp);
                String candSignature = typeSignature(candProp);
                if (!baseSignature.equals(candSignature)) {
                    breaking.add(new BreakingChange(
                            "REQUEST_PROPERTY_TYPE_CHANGED",
                            schemaLoc + "." + propName,
                            "Request property type changed from '" + baseSignature + "' to '" + candSignature + "'"));
                }
            }
        }
        for (Map.Entry<String, JsonNode> candEntry : propertiesOf(candProps)) {
            String propName = candEntry.getKey();
            if (baseProps.get(propName) == null && !candReqProps.contains(propName)) {
                nonBreaking.add("Added optional request property " + schemaLoc + "." + propName);
            }
        }
    }

    private static void compareResponseSchema(
            String schemaLoc,
            JsonNode baseSchema,
            JsonNode candSchema,
            List<BreakingChange> breaking,
            List<String> nonBreaking
    ) {
        Set<String> baseReqProps = toStringSet(baseSchema.get("required"));
        Set<String> candReqProps = toStringSet(candSchema.get("required"));

        JsonNode baseProps = baseSchema.path("properties");
        JsonNode candProps = candSchema.path("properties");

        for (Map.Entry<String, JsonNode> propEntry : propertiesOf(baseProps)) {
            String propName = propEntry.getKey();
            JsonNode baseProp = propEntry.getValue();
            JsonNode candProp = candProps.get(propName);
            if (candProp == null) {
                breaking.add(new BreakingChange(
                        "RESPONSE_PROPERTY_REMOVED",
                        schemaLoc + "." + propName,
                        "Response property '" + propName + "' was removed"));
                continue;
            }
            if (baseReqProps.contains(propName) && !candReqProps.contains(propName)) {
                breaking.add(new BreakingChange(
                        "RESPONSE_PROPERTY_MADE_OPTIONAL",
                        schemaLoc + "." + propName,
                        "Required response property '" + propName + "' became optional"));
            }
            boolean baseNullable = isNullable(baseProp);
            boolean candNullable = isNullable(candProp);
            if (!baseNullable && candNullable) {
                breaking.add(new BreakingChange(
                        "RESPONSE_PROPERTY_MADE_NULLABLE",
                        schemaLoc + "." + propName,
                        "Non-null response property '" + propName + "' became nullable"));
            }
            String baseSig = nonNullTypeSignature(baseProp);
            String candSig = nonNullTypeSignature(candProp);
            if (!baseSig.equals(candSig)) {
                breaking.add(new BreakingChange(
                        "RESPONSE_PROPERTY_TYPE_CHANGED",
                        schemaLoc + "." + propName,
                        "Response property type changed from '" + baseSig + "' to '" + candSig + "'"));
            }
        }

        for (Map.Entry<String, JsonNode> candEntry : propertiesOf(candProps)) {
            if (baseProps.get(candEntry.getKey()) == null) {
                nonBreaking.add("Added response property " + schemaLoc + "." + candEntry.getKey());
            }
        }
    }

    private static JsonNode findParameter(JsonNode params, String name, String in) {
        for (JsonNode p : elementsOf(params)) {
            if (name.equals(p.path("name").asText()) && in.equals(p.path("in").asText())) {
                return p;
            }
        }
        return null;
    }

    private static Set<String> expandTransitiveRefs(Set<String> roots, JsonNode schemas) {
        Set<String> visited = new HashSet<>(roots);
        List<String> queue = new ArrayList<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            JsonNode schema = schemas.get(current);
            if (schema == null) {
                continue;
            }
            Set<String> childRefs = new HashSet<>();
            collectRefNames(schema, childRefs);
            for (String ref : childRefs) {
                if (visited.add(ref)) {
                    queue.add(ref);
                }
            }
        }
        return visited;
    }

    private static void collectRefNames(JsonNode node, Set<String> target) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            JsonNode refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String ref = refNode.asText();
                String prefix = "#/components/schemas/";
                if (ref.startsWith(prefix)) {
                    target.add(ref.substring(prefix.length()));
                }
            }
            for (Map.Entry<String, JsonNode> entry : propertiesOf(node)) {
                collectRefNames(entry.getValue(), target);
            }
        } else if (node.isArray()) {
            for (JsonNode item : elementsOf(node)) {
                collectRefNames(item, target);
            }
        }
    }

    private static boolean isNullable(JsonNode propSchema) {
        JsonNode typeNode = propSchema.get("type");
        if (typeNode != null) {
            if (typeNode.isTextual() && "null".equals(typeNode.asText())) {
                return true;
            }
            if (typeNode.isArray()) {
                for (JsonNode el : elementsOf(typeNode)) {
                    if ("null".equals(el.asText())) {
                        return true;
                    }
                }
            }
        }
        JsonNode anyOf = propSchema.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            for (JsonNode el : elementsOf(anyOf)) {
                if ("null".equals(el.path("type").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String primaryType(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) {
            return "unknown";
        }
        if (schema.has("$ref")) {
            return schema.get("$ref").asText();
        }
        JsonNode t = schema.get("type");
        if (t != null && t.isTextual()) {
            return t.asText();
        }
        if (t != null && t.isArray()) {
            for (JsonNode el : elementsOf(t)) {
                if (!"null".equals(el.asText())) {
                    return el.asText();
                }
            }
        }
        return "unknown";
    }

    private static String typeSignature(JsonNode propSchema) {
        return nonNullTypeSignature(propSchema) + (isNullable(propSchema) ? "|null" : "");
    }

    private static String nonNullTypeSignature(JsonNode propSchema) {
        if (propSchema == null || propSchema.isMissingNode()) {
            return "unknown";
        }
        if (propSchema.has("const")) {
            return "const:" + propSchema.get("const").asText();
        }
        if (propSchema.has("$ref")) {
            return propSchema.get("$ref").asText();
        }
        if (propSchema.has("anyOf")) {
            for (JsonNode branch : elementsOf(propSchema.get("anyOf"))) {
                if (!"null".equals(branch.path("type").asText())) {
                    return nonNullTypeSignature(branch);
                }
            }
        }
        String primary = primaryType(propSchema);
        if ("array".equals(primary)) {
            return "array<" + typeSignature(propSchema.path("items")) + ">";
        }
        return primary;
    }

    private static Set<String> toStringSet(JsonNode arrayNode) {
        Set<String> set = new HashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode el : elementsOf(arrayNode)) {
                set.add(el.asText());
            }
        }
        return set;
    }

    private static Iterable<Map.Entry<String, JsonNode>> propertiesOf(JsonNode obj) {
        if (obj == null || !obj.isObject()) {
            return List.of();
        }
        return obj.properties();
    }

    private static Iterable<JsonNode> elementsOf(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        return arr.values();
    }
}
