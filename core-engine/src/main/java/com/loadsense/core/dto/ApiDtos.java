package com.loadsense.core.dto;

import com.loadsense.core.entity.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiDtos {
    private ApiDtos() {}
    public record Credentials(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,40}") String username,
                              @NotBlank @Size(min=8, max=72) String password) {}
    public record AuthResponse(String token, String username, UUID userId, Instant expiresAt) {}
    public record ConfigRequest(
        @NotBlank @Size(max=120) String name,
        @NotBlank @Size(max=2048) String targetUrl,
        @NotBlank @Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String httpMethod,
        Map<String,String> headers, @Size(max=65536) String body,
        @Min(1) @Max(300) int virtualUsers, @Min(1) @Max(600) int durationSeconds,
        @Min(1) @Max(500) int requestsPerSecond, @Min(100) @Max(30000) int timeoutMs) {}
    public record ConfigView(UUID id, String name, String targetUrl, String httpMethod,
        Map<String,String> headers, String body, int virtualUsers, int durationSeconds,
        int requestsPerSecond, int timeoutMs, Instant createdAt, Instant updatedAt) {}
    public record StartRequest(@AssertTrue(message="Cần xác nhận quyền kiểm thử API đích") boolean authorizedTarget,
                               UUID mockDataSetId) {}
    public record DataSetRequest(@NotBlank @Size(max=120) String name,
                                @Size(max=65536) String schemaJson, @NotBlank @Size(max=262144) String payloadsJson) {}
    public record MetricView(UUID id, Instant sampleTime, double elapsedSeconds, long totalRequests,
        long failedRequests, double throughput, double avgLatencyMs, double p50LatencyMs,
        double p95LatencyMs, double errorRate, int activeUsers) {
        public static MetricView of(TestMetric m) {
            return new MetricView(m.id,m.sampleTime,m.elapsedSeconds,m.totalRequests,m.failedRequests,
                m.throughput,m.avgLatencyMs,m.p50LatencyMs,m.p95LatencyMs,m.errorRate,m.activeUsers);
        }
    }
    public record ExecutionView(UUID id, UUID testConfigId, String name, String targetUrl,
        String httpMethod, int virtualUsers, int durationSeconds, int requestsPerSecond,
        ExecutionStatus status, Instant startTime, Instant endTime, Instant createdAt,
        String failureReason, long totalRequests, long failedRequests, double avgLatencyMs,
        double p50LatencyMs, double p95LatencyMs, double throughput, double errorRate) {}
    public record ExecutionEvent(ExecutionView execution, MetricView metric) {}
    public record ErrorResponse(String code, String message, Map<String,String> fields) {}
}
