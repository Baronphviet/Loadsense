package com.loadsense.core.service;

import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.ExecutionStatus;
import java.util.ArrayList;
import java.util.List;

/** A deliberately limited representation of an execution sent to the AI service. */
public record AiAnalysisInput(long totalRequests, long failedRequests, double throughput,
    double avgLatencyMs, double p50LatencyMs, double p95LatencyMs, double errorRate,
    ExecutionStatus status, LoadConfig loadConfig, List<Sample> samples) {
    private static final int MAX_SAMPLES = 60;
    public record LoadConfig(int virtualUsers, int durationSeconds, int requestsPerSecond, int timeoutMs) {}
    public record Sample(double elapsedSeconds, long totalRequests, long failedRequests,
        double throughput, double avgLatencyMs, double p50LatencyMs, double p95LatencyMs,
        double errorRate, int activeUsers) {
        static Sample from(MetricView m) {
            return new Sample(m.elapsedSeconds(), m.totalRequests(), m.failedRequests(), m.throughput(),
                m.avgLatencyMs(), m.p50LatencyMs(), m.p95LatencyMs(), m.errorRate(), m.activeUsers());
        }
    }
    public static AiAnalysisInput build(ConfigRequest config, ExecutionView execution, List<MetricView> metrics) {
        var selected = new ArrayList<Sample>();
        int count = Math.min(metrics.size(), MAX_SAMPLES);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : (int)((long)i * (metrics.size() - 1) / (count - 1));
            selected.add(Sample.from(metrics.get(index)));
        }
        return new AiAnalysisInput(execution.totalRequests(), execution.failedRequests(), execution.throughput(),
            execution.avgLatencyMs(), execution.p50LatencyMs(), execution.p95LatencyMs(), execution.errorRate(),
            execution.status(), new LoadConfig(config.virtualUsers(), config.durationSeconds(),
                config.requestsPerSecond(), config.timeoutMs()), List.copyOf(selected));
    }
}
