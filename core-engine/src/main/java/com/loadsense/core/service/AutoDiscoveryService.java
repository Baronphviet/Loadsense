package com.loadsense.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.client.TargetHttpClient;
import com.loadsense.core.dto.ApiDtos.*;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thăm dò URL mục tiêu (Pre-flight Probe) và tự động khám phá API/route cùng origin
 * từ OpenAPI/Swagger, health endpoints hoặc HTML để tạo đề xuất kiểm thử 1 chạm.
 */
@Service
public class AutoDiscoveryService {
    private static final int DEFAULT_VUS = 20;
    private static final int DEFAULT_DURATION = 12;
    private static final int DEFAULT_RPS = 60;
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int DISCOVER_SPEC_TIMEOUT_MS = 1500;
    private static final int MAX_ENDPOINTS = 10;

    private static final List<String> PRIORITY_VARS = List.of(
        "token", "accessToken", "access_token", "id", "itemId", "userId", "sessionId", "code"
    );
    private static final List<String> SPEC_AND_HEALTH_PATHS = List.of(
        "/openapi.json", "/v3/api-docs", "/swagger.json", "/api/health"
    );
    private static final Pattern FORM_TAG = Pattern.compile(
        "<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_ATTR = Pattern.compile(
        "action\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_ATTR = Pattern.compile(
        "method\\s*=\\s*[\"']([a-zA-Z]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_PATH_REF = Pattern.compile(
        "(?:href|src|action|fetch\\(|axios\\.(?:get|post|put|delete|patch)\\()\\s*[=\\s(]*[\"'](/(?:api|v[1-9]|demo)[a-zA-Z0-9_./?=&%-]*)[\"']",
        Pattern.CASE_INSENSITIVE);

    private final TargetPolicy policy;
    private final TargetHttpClient http;
    private final ObjectMapper json;

    public AutoDiscoveryService(TargetPolicy policy, TargetHttpClient http, ObjectMapper json) {
        this.policy = policy;
        this.http = http;
        this.json = json;
    }

    /** Thực hiện 1 request thăm dò tới URL đích, đo độ trễ và trích xuất gợi ý biến JSON. */
    public ProbeResponse probe(UUID owner, ProbeRequest req) {
        return runProbeInternal(
            owner, req.targetUrl(), req.resolvedMethod(), req.headers(), req.body(),
            PROBE_TIMEOUT_MS, true
        ).response();
    }

    /** Khám phá URL gốc, tài liệu OpenAPI/Swagger, endpoint sức khỏe và route từ HTML cùng origin. */
    public DiscoverResponse discover(UUID owner, DiscoverRequest req) {
        InternalProbe primary = runProbeInternal(
            owner, req.targetUrl(), "GET", Map.of(), null, PROBE_TIMEOUT_MS, true
        );
        ProbeResponse primaryResp = primary.response();
        URI primaryUri = URI.create(primaryResp.targetUrl());
        String origin = buildOrigin(primaryUri);

        List<DiscoveredEndpoint> endpoints = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        if (primaryResp.reachable()) {
            addUniqueEndpoint(endpoints, seenKeys, new DiscoveredEndpoint(
                endpointName("Target chính", primaryUri.getPath(), "GET"),
                primaryResp.targetUrl(),
                "GET",
                "TARGET_PROBE",
                primaryResp.status(),
                primaryResp.latencyMs(),
                primaryResp.suggestedExtractPaths(),
                null,
                defaultConfig(endpointName("Auto-Audit", primaryUri.getPath(), "GET"),
                    primaryResp.targetUrl(), "GET", null, List.of())
            ));
        } else {
            notes.add("Không thể kết nối trực tiếp tới URL gốc: " + (primaryResp.error() == null ? "timeout/network" : primaryResp.error()));
        }

        boolean foundSpec = false;
        if (primary.rawBody() != null && looksLikeOpenApi(primary.rawBody())) {
            foundSpec = parseOpenApi(primary.rawBody(), origin, endpoints, seenKeys, notes);
        }

        if (!foundSpec) {
            for (String suffix : SPEC_AND_HEALTH_PATHS) {
                if (endpoints.size() >= MAX_ENDPOINTS) break;
                String candidate = origin + suffix;
                if (candidate.equals(primaryResp.targetUrl())) continue;
                InternalProbe sub = runProbeInternal(owner, candidate, "GET", Map.of(), null, DISCOVER_SPEC_TIMEOUT_MS, false);
                ProbeResponse subResp = sub.response();
                if (!subResp.reachable() || subResp.status() < 200 || subResp.status() >= 400) continue;

                if (!"/api/health".equals(suffix) && sub.rawBody() != null && looksLikeOpenApi(sub.rawBody())) {
                    foundSpec = parseOpenApi(sub.rawBody(), origin, endpoints, seenKeys, notes);
                    if (foundSpec) break;
                } else {
                    addUniqueEndpoint(endpoints, seenKeys, new DiscoveredEndpoint(
                        endpointName("Health", suffix, "GET"),
                        candidate,
                        "GET",
                        "WELL_KNOWN",
                        subResp.status(),
                        subResp.latencyMs(),
                        subResp.suggestedExtractPaths(),
                        null,
                        defaultConfig("Health Audit · " + suffix, candidate, "GET", null, List.of())
                    ));
                }
            }
        }

        if (primary.rawBody() != null && isHtml(primaryResp.contentType()) && endpoints.size() < MAX_ENDPOINTS) {
            extractFromHtml(primary.rawBody(), primaryUri, origin, endpoints, seenKeys, notes);
        }

        appendChainedSuggestion(primaryResp, origin, endpoints, seenKeys, notes);

        if (endpoints.isEmpty()) {
            endpoints.add(new DiscoveredEndpoint(
                endpointName("Target", primaryUri.getPath(), "GET"),
                primaryResp.targetUrl(),
                "GET",
                "TARGET_PROBE",
                primaryResp.reachable() ? primaryResp.status() : null,
                primaryResp.reachable() ? primaryResp.latencyMs() : null,
                List.of(),
                null,
                defaultConfig("Auto-Audit · " + primaryUri.getHost(), primaryResp.targetUrl(), "GET", null, List.of())
            ));
        }

        return new DiscoverResponse(primaryResp, endpoints, notes);
    }

    private record InternalProbe(ProbeResponse response, String rawBody) {}

    private InternalProbe runProbeInternal(UUID owner, String rawUrl, String method, Map<String, String> headers,
                                          String body, int timeoutMs, boolean autoApprove) {
        URI validated = policy.validateEndpointForProbe(rawUrl, method, headers, body, owner, autoApprove);
        String canonicalUrl = validated.toString();
        try {
            TargetHttpClient.ProbeResult res = http.probeWithHeaders(method, canonicalUrl, headers, body, timeoutMs);
            String ct = res.headers().getOrDefault("content-type", "");
            List<String> paths = extractJsonPaths(res.body());
            String preview = truncate(res.body(), 1500);
            ProbeResponse resp = new ProbeResponse(
                canonicalUrl, method, res.status(), res.latencyMs(), ct,
                res.headers(), paths, preview, true, null
            );
            return new InternalProbe(resp, res.body());
        } catch (Exception e) {
            String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            ProbeResponse resp = new ProbeResponse(
                canonicalUrl, method, 0, 0L, "", Map.of(), List.of(), null, false, msg
            );
            return new InternalProbe(resp, null);
        }
    }

    private boolean looksLikeOpenApi(String rawBody) {
        String trimmed = rawBody.stripLeading();
        if (!trimmed.startsWith("{")) return false;
        try {
            JsonNode root = json.readTree(trimmed);
            return root.isObject() && root.has("paths") && (root.has("openapi") || root.has("swagger"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean parseOpenApi(String rawBody, String origin, List<DiscoveredEndpoint> out,
                                 Set<String> seenKeys, List<String> notes) {
        try {
            JsonNode root = json.readTree(rawBody);
            JsonNode paths = root.path("paths");
            if (!paths.isObject() || paths.isEmpty()) return false;
            int before = out.size();
            Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
            while (pathIt.hasNext() && out.size() < MAX_ENDPOINTS) {
                Map.Entry<String, JsonNode> pathEntry = pathIt.next();
                String apiPath = pathEntry.getKey();
                if (!apiPath.startsWith("/")) apiPath = "/" + apiPath;
                String sampleUrl = origin + apiPath.replaceAll("\\{[^}]+}", "1");
                JsonNode methods = pathEntry.getValue();
                if (!methods.isObject()) continue;
                for (String method : List.of("get", "post", "put", "patch", "delete")) {
                    if (out.size() >= MAX_ENDPOINTS) break;
                    JsonNode op = methods.path(method);
                    if (!op.isObject()) continue;
                    String upper = method.toUpperCase(Locale.ROOT);
                    String summary = op.path("summary").asText("").trim();
                    String title = summary.isEmpty() ? endpointName("OpenAPI", apiPath, upper) : summary;
                    String sampleBody = ("GET".equals(upper) || "HEAD".equals(upper)) ? null : buildOpenApiSampleBody(op, root);
                    ConfigRequest cfg = defaultConfig(title, sampleUrl, upper, sampleBody, List.of());
                    addUniqueEndpoint(out, seenKeys, new DiscoveredEndpoint(
                        title, sampleUrl, upper, "OPENAPI", null, null, List.of(), sampleBody, cfg
                    ));
                }
            }
            if (out.size() > before) {
                notes.add("Đã phát hiện đặc tả OpenAPI/Swagger và sinh " + (out.size() - before) + " kịch bản endpoint.");
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String buildOpenApiSampleBody(JsonNode op, JsonNode root) {
        JsonNode schema = op.path("requestBody").path("content").path("application/json").path("schema");
        if (schema.isMissingNode() || !schema.isObject()) return "{}";
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText("");
            if (ref.startsWith("#/")) {
                JsonNode resolved = root;
                for (String seg : ref.substring(2).split("/")) {
                    resolved = resolved.path(seg);
                }
                if (resolved.isObject()) schema = resolved;
            }
        }
        JsonNode props = schema.path("properties");
        if (!props.isObject() || props.isEmpty()) return "{}";
        Map<String, Object> sample = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
        int count = 0;
        while (fields.hasNext() && count < 8) {
            var entry = fields.next();
            String type = entry.getValue().path("type").asText("string");
            sample.put(entry.getKey(), switch (type) {
                case "integer" -> 1;
                case "number" -> 1.0;
                case "boolean" -> true;
                case "array" -> List.of();
                default -> entry.getKey() + "-sample";
            });
            count++;
        }
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(sample);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void extractFromHtml(String html, URI baseUri, String origin, List<DiscoveredEndpoint> out,
                                 Set<String> seenKeys, List<String> notes) {
        int before = out.size();
        Matcher formMatcher = FORM_TAG.matcher(html);
        while (formMatcher.find() && out.size() < MAX_ENDPOINTS) {
            String attrs = formMatcher.group(1);
            Matcher actionM = ACTION_ATTR.matcher(attrs);
            if (!actionM.find()) continue;
            String rawAction = actionM.group(1).trim();
            Matcher methodM = METHOD_ATTR.matcher(attrs);
            String method = methodM.find() ? methodM.group(1).toUpperCase(Locale.ROOT) : "GET";
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) method = "GET";
            addResolvedHtmlEndpoint(baseUri, origin, rawAction, method, "HTML_FORM", out, seenKeys);
        }
        Matcher apiMatcher = API_PATH_REF.matcher(html);
        while (apiMatcher.find() && out.size() < MAX_ENDPOINTS) {
            addResolvedHtmlEndpoint(baseUri, origin, apiMatcher.group(1), "GET", "HTML_LINK", out, seenKeys);
        }
        if (out.size() > before) {
            notes.add("Đã trích xuất " + (out.size() - before) + " route/form cùng origin từ nội dung HTML.");
        }
    }

    private void addResolvedHtmlEndpoint(URI baseUri, String origin, String rawRef, String method, String source,
                                         List<DiscoveredEndpoint> out, Set<String> seenKeys) {
        if (rawRef == null || rawRef.isBlank() || rawRef.startsWith("#") || rawRef.startsWith("javascript:")) return;
        try {
            URI resolved = baseUri.resolve(rawRef);
            if (!origin.equals(buildOrigin(resolved))) return;
            String target = resolved.toString();
            String name = endpointName("HTML", resolved.getPath(), method);
            String body = "GET".equals(method) ? null : "{}";
            addUniqueEndpoint(out, seenKeys, new DiscoveredEndpoint(
                name, target, method, source, null, null, List.of(), body,
                defaultConfig(name, target, method, body, List.of())
            ));
        } catch (Exception ignored) {
            // Bỏ qua đường dẫn HTML không hợp lệ
        }
    }

    private void appendChainedSuggestion(ProbeResponse primaryResp, String origin, List<DiscoveredEndpoint> out,
                                         Set<String> seenKeys, List<String> notes) {
        if (primaryResp.suggestedExtractPaths().isEmpty() || out.size() >= MAX_ENDPOINTS) return;
        String firstPath = primaryResp.suggestedExtractPaths().get(0);
        String varName = firstPath.contains(".")
            ? firstPath.substring(firstPath.lastIndexOf('.') + 1).replaceAll("[^a-zA-Z0-9_]", "")
            : "token";
        if (varName.isBlank() || !Character.isLetter(varName.charAt(0))) varName = "token";

        String followUrl = out.stream()
            .map(DiscoveredEndpoint::targetUrl)
            .filter(u -> !u.equals(primaryResp.targetUrl()))
            .findFirst()
            .orElse(origin + "/demo/echo");
        String sep = followUrl.contains("?") ? "&" : "?";
        List<ScenarioStep> chain = List.of(
            new ScenarioStep("Bước 1 · Lấy biến " + varName, primaryResp.targetUrl(), primaryResp.httpMethod(),
                Map.of(), "", varName, firstPath),
            new ScenarioStep("Bước 2 · Dùng {{" + varName + "}}", followUrl + sep + varName + "={{" + varName + "}}", "GET",
                Map.of("Authorization", "Bearer {{" + varName + "}}"), "", "", "")
        );
        ConfigRequest cfg = defaultConfig("Chuỗi Chaining · {{" + varName + "}}", primaryResp.targetUrl(),
            primaryResp.httpMethod(), null, chain);
        addUniqueEndpoint(out, seenKeys, new DiscoveredEndpoint(
            "Chuỗi 2 bước (tự trích xuất {{" + varName + "}})",
            primaryResp.targetUrl(),
            primaryResp.httpMethod(),
            "CHAINED_FLOW",
            primaryResp.status(),
            primaryResp.latencyMs(),
            primaryResp.suggestedExtractPaths(),
            null,
            cfg
        ));
        notes.add("Phát hiện khóa JSON " + firstPath + " trong phản hồi; đã tạo sẵn kịch bản Scenario Chaining 2 bước.");
    }

    private List<String> extractJsonPaths(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return List.of();
        String trimmed = rawBody.stripLeading();
        if (!trimmed.startsWith("{")) return List.of();
        try {
            JsonNode root = json.readTree(trimmed);
            if (!root.isObject()) return List.of();
            List<String> priority = new ArrayList<>();
            List<String> others = new ArrayList<>();
            collectScalarPaths(root, "$", 0, priority, others);
            List<String> merged = new ArrayList<>(priority);
            for (String item : others) {
                if (!merged.contains(item)) merged.add(item);
            }
            return merged.size() > 8 ? List.copyOf(merged.subList(0, 8)) : List.copyOf(merged);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void collectScalarPaths(JsonNode node, String prefix, int depth, List<String> priority, List<String> others) {
        if (depth > 2 || !node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            String path = prefix + "." + key;
            if (val.isValueNode() && !val.isNull()) {
                if (PRIORITY_VARS.contains(key)) priority.add(path);
                else others.add(path);
            } else if (val.isObject() && depth < 2) {
                collectScalarPaths(val, path, depth + 1, priority, others);
            }
        }
    }

    private ConfigRequest defaultConfig(String name, String url, String method, String body, List<ScenarioStep> steps) {
        Map<String, String> headers = (body != null && !body.isBlank())
            ? Map.of("Content-Type", "application/json")
            : Map.of();
        return new ConfigRequest(
            truncate(name, 120), url, method, headers, body,
            DEFAULT_VUS, DEFAULT_DURATION, DEFAULT_RPS, DEFAULT_TIMEOUT_MS,
            "STEP_STRESS", 0, steps
        );
    }

    private void addUniqueEndpoint(List<DiscoveredEndpoint> out, Set<String> seenKeys, DiscoveredEndpoint ep) {
        String key = ep.source() + ":" + ep.httpMethod() + ":" + ep.targetUrl();
        if (seenKeys.add(key)) out.add(ep);
    }

    private String buildOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    private boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private String endpointName(String prefix, String path, String method) {
        String cleanPath = (path == null || path.isBlank()) ? "/" : path;
        return truncate(prefix + " · " + method + " " + cleanPath, 100);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
