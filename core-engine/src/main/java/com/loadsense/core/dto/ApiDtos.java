package com.loadsense.core.dto;

import com.loadsense.core.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiDtos {
    private ApiDtos() {}
    public record Credentials(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,40}") String username,
                              @NotBlank @Size(min=8, max=72) String password) {}
    public record AuthResponse(String token, String username, UUID userId, Instant expiresAt) {}
    public record ScenarioStep(
        @Size(max=80) String name,
        @NotBlank @Size(max=2048) String targetUrl,
        @NotBlank @Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String httpMethod,
        Map<String,String> headers,
        @Size(max=65536) String body,
        @Pattern(regexp="^$|^[a-zA-Z_][a-zA-Z0-9_]{0,63}$", message="Tên biến trích xuất chỉ gồm chữ, số và _") String extractVar,
        @Size(max=256) String extractPath) {}
    public record ConfigRequest(
        @NotBlank @Size(max=120) String name,
        @NotBlank @Size(max=2048) String targetUrl,
        @NotBlank @Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String httpMethod,
        Map<String,String> headers, @Size(max=65536) String body,
        @Min(1) @Max(300) int virtualUsers, @Min(1) @Max(600) int durationSeconds,
        @Min(1) @Max(500) int requestsPerSecond, @Min(100) @Max(30000) int timeoutMs,
        @Pattern(regexp="CONSTANT|RAMP_UP|STEP_STRESS|SPIKE") String loadProfile,
        @Min(0) @Max(600) Integer rampUpSeconds,
        @Size(max=3) List<@Valid ScenarioStep> steps) {
        public ConfigRequest(String name, String targetUrl, String httpMethod, Map<String,String> headers,
            String body, int virtualUsers, int durationSeconds, int requestsPerSecond, int timeoutMs) {
            this(name, targetUrl, httpMethod, headers, body, virtualUsers, durationSeconds, requestsPerSecond, timeoutMs, "CONSTANT", 0, List.of());
        }
        public String resolvedProfile() {
            return (loadProfile == null || loadProfile.isBlank()) ? "CONSTANT" : loadProfile;
        }
        public int resolvedRampUpSeconds() {
            return rampUpSeconds == null ? 0 : rampUpSeconds;
        }
        public List<ScenarioStep> resolvedSteps() {
            return steps == null ? List.of() : steps;
        }
    }
    public record ConfigView(UUID id, String name, String targetUrl, String httpMethod,
        Map<String,String> headers, String body, int virtualUsers, int durationSeconds,
        int requestsPerSecond, int timeoutMs, String loadProfile, int rampUpSeconds,
        List<ScenarioStep> steps, Instant createdAt, Instant updatedAt) {}
    public record StartRequest(@AssertTrue(message="Cần xác nhận quyền kiểm thử API đích") boolean authorizedTarget,
                               UUID mockDataSetId, Boolean autoAiAnalyze) {}
    public record UserSettingsDto(
        boolean autoAiAnalyze,
        boolean earlyAnomalyEnabled,
        @DecimalMin("0.5") @DecimalMax("100.0") double anomalyErrorRateThreshold,
        @Min(50) @Max(30000) int anomalyP95ThresholdMs,
        boolean autoStopOnAnomaly) {}
    public record ApproveHostRequest(@NotBlank @Size(max=253) String host,
        @AssertTrue(message="Cần xác nhận quyền kiểm thử host") boolean authorized) {}
    public record DataSetRequest(@NotBlank @Size(max=120) String name,
                                @Size(max=65536) String schemaJson, @NotBlank @Size(max=262144) String payloadsJson) {}
    public record MetricView(UUID id, Instant sampleTime, double elapsedSeconds, long totalRequests,
        long failedRequests, double throughput, double avgLatencyMs, double p50LatencyMs,
        double p95LatencyMs, double errorRate, int activeUsers,
        double p99LatencyMs, double maxLatencyMs, double intervalP95Ms, double intervalErrorRate) {
        public MetricView(UUID id, Instant sampleTime, double elapsedSeconds, long totalRequests,
            long failedRequests, double throughput, double avgLatencyMs, double p50LatencyMs,
            double p95LatencyMs, double errorRate, int activeUsers) {
            this(id, sampleTime, elapsedSeconds, totalRequests, failedRequests, throughput,
                avgLatencyMs, p50LatencyMs, p95LatencyMs, errorRate, activeUsers, 0, 0, 0, 0);
        }
        public static MetricView of(TestMetric m) {
            return new MetricView(m.id,m.sampleTime,m.elapsedSeconds,m.totalRequests,m.failedRequests,
                m.throughput,m.avgLatencyMs,m.p50LatencyMs,m.p95LatencyMs,m.errorRate,m.activeUsers,
                m.p99LatencyMs,m.maxLatencyMs,m.intervalP95Ms,m.intervalErrorRate);
        }
    }
    public record ExecutionView(UUID id, UUID testConfigId, String name, String targetUrl,
        String httpMethod, int virtualUsers, int durationSeconds, int requestsPerSecond,
        ExecutionStatus status, Instant startTime, Instant endTime, Instant createdAt,
        String failureReason, long totalRequests, long failedRequests, double avgLatencyMs,
        double p50LatencyMs, double p95LatencyMs, double throughput, double errorRate,
        UUID mockDataSetId, Map<String, Long> errorBreakdown, String anomalyWarning,
        String loadProfile, double p99LatencyMs, double maxLatencyMs, String stopReason) {
        public ExecutionView(UUID id, UUID testConfigId, String name, String targetUrl,
            String httpMethod, int virtualUsers, int durationSeconds, int requestsPerSecond,
            ExecutionStatus status, Instant startTime, Instant endTime, Instant createdAt,
            String failureReason, long totalRequests, long failedRequests, double avgLatencyMs,
            double p50LatencyMs, double p95LatencyMs, double throughput, double errorRate,
            UUID mockDataSetId, Map<String, Long> errorBreakdown, String anomalyWarning) {
            this(id, testConfigId, name, targetUrl, httpMethod, virtualUsers, durationSeconds,
                requestsPerSecond, status, startTime, endTime, createdAt, failureReason,
                totalRequests, failedRequests, avgLatencyMs, p50LatencyMs, p95LatencyMs,
                throughput, errorRate, mockDataSetId, errorBreakdown, anomalyWarning,
                "CONSTANT", 0, 0, null);
        }
    }
    public record ExecutionEvent(ExecutionView execution, MetricView metric) {}
    public record ProbeRequest(
        @NotBlank @Size(max=2048) String targetUrl,
        @Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String httpMethod,
        Map<String,String> headers,
        @Size(max=65536) String body,
        @AssertTrue(message="Cần xác nhận quyền kiểm thử API đích") boolean authorizedTarget) {
        public String resolvedMethod() {
            return (httpMethod == null || httpMethod.isBlank()) ? "GET" : httpMethod;
        }
    }
    public record ProbeResponse(
        String targetUrl, String httpMethod, int status, long latencyMs, String contentType,
        Map<String,String> responseHeaders, List<String> suggestedExtractPaths,
        String bodyPreview, boolean reachable, String error) {}
    public record DiscoverRequest(
        @NotBlank @Size(max=2048) String targetUrl,
        @AssertTrue(message="Cần xác nhận quyền kiểm thử URL/host đích") boolean authorizedTarget) {}
    public record DiscoveredEndpoint(
        String name, String targetUrl, String httpMethod, String source,
        Integer probeStatus, Long probeLatencyMs, List<String> suggestedExtractPaths,
        String sampleBody, ConfigRequest proposedConfig) {}
    public record DiscoverResponse(
        ProbeResponse primaryProbe, List<DiscoveredEndpoint> endpoints, List<String> notes) {}
    public record CreateSuiteRequest(
        @NotBlank @Size(max=2048) String targetUrl,
        @Size(max=160) String name,
        @AssertTrue(message="Cần xác nhận quyền kiểm thử URL đích") boolean authorizedTarget,
        Integer maxCases) {
        public CreateSuiteRequest(String targetUrl, String name, boolean authorizedTarget) {
            this(targetUrl, name, authorizedTarget, 100);
        }
    }
    public record SuiteStepLog(int stepNumber, String action, String status, long durationMs, String detail) {}
    public record SuitePreview(
        String finalUrl, int httpStatus, long latencyMs, String contentType,
        int contentBytes, String pageTitle, List<String> headings, String excerpt,
        String htmlSnapshot, List<String> touchedElements) {
        public SuitePreview(String finalUrl, int httpStatus, long latencyMs, String contentType,
            int contentBytes, String pageTitle, List<String> headings, String excerpt) {
            this(finalUrl, httpStatus, latencyMs, contentType, contentBytes, pageTitle, headings, excerpt, "", List.of());
        }
    }
    public record SuiteDiagnosis(
        String previewSummary, String error, String cause, String fix, String fixPrompt, boolean aiGenerated) {
        public SuiteDiagnosis(String error, String cause, String fix, String fixPrompt) {
            this("", error, cause, fix, fixPrompt, false);
        }
    }
    public record SuiteTestCase(
        String id, String priority, String name, String description,
        String targetUrl, String httpMethod, List<String> steps,
        String status, Instant lastRunAt, Long latencyMs, String failureSummary,
        List<SuiteStepLog> runLog, SuitePreview preview, String playwrightCode, SuiteDiagnosis diagnosis,
        List<String> visitedPages, String parentPath, int depth, String actionLabel) {
        public SuiteTestCase(String id, String priority, String name, String description,
            String targetUrl, String httpMethod, List<String> steps,
            String status, Instant lastRunAt, Long latencyMs, String failureSummary,
            List<SuiteStepLog> runLog, SuitePreview preview, String playwrightCode, SuiteDiagnosis diagnosis) {
            this(id, priority, name, description, targetUrl, httpMethod, steps, status, lastRunAt, latencyMs,
                failureSummary, runLog, preview, playwrightCode, diagnosis, List.of(), null, 0, null);
        }
    }
    public record SuiteUseCaseGroup(String useCase, String slug, List<SuiteTestCase> testCases) {}
    public record UpdateSuitePlanRequest(String planYaml, List<SuiteUseCaseGroup> useCases) {}
    public record SuiteRunRequest(List<String> caseIds) {}
    public record TestSuiteSummary(
        UUID id, String name, String targetUrl, int totalCases, int passedCases,
        int failedCases, int runningCases, Instant lastRunAt, Instant updatedAt) {}
    public record TestSuiteView(
        UUID id, String name, String targetUrl, String planYaml, List<SuiteUseCaseGroup> useCases,
        int totalCases, int passedCases, int failedCases, int runningCases,
        Instant lastRunAt, Instant createdAt, Instant updatedAt) {}
    public record ErrorResponse(String code, String message, Map<String,String> fields) {}
}
