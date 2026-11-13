package com.loadsense.core.service;

import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.*;
import com.loadsense.core.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class ExecutionStore {
    private final TestExecutionRepository executions;
    private final TestMetricRepository metrics;
    private final TestReportRepository reports;
    private final TestConfigService configs;
    private final ObjectMapper json;
    public ExecutionStore(TestExecutionRepository executions, TestMetricRepository metrics,
        TestReportRepository reports, TestConfigService configs, ObjectMapper json) {
        this.executions=executions; this.metrics=metrics; this.reports=reports; this.configs=configs; this.json=json;
    }
    public TestExecution owned(UUID id,UUID owner) { return executions.findByIdAndUserId(id,owner).orElseThrow(ApiException::missing); }
    public List<ExecutionView> list(UUID owner) { return executions.findTop100ByUserIdOrderByCreatedAtDesc(owner).stream().map(this::view).toList(); }
    public ExecutionView get(UUID id,UUID owner) { return view(owned(id,owner)); }
    @Transactional public TestExecution create(TestConfig c, ConfigRequest snapshot) {
        return create(c, snapshot, null);
    }
    @Transactional public TestExecution create(TestConfig c, ConfigRequest snapshot, UUID mockDataSetId) {
        var e=new TestExecution(); e.testConfigId=c.id; e.userId=c.userId; e.status=ExecutionStatus.PENDING;
        e.mockDataSetId=mockDataSetId;
        e.configSnapshot=configs.encode(snapshot); return executions.saveAndFlush(e);
    }
    @Transactional public void running(UUID id) {
        var e=executions.findById(id).orElseThrow(); e.status=ExecutionStatus.RUNNING; e.startTime=Instant.now(); executions.save(e);
    }
    @Transactional public ExecutionEvent sample(UUID id,TestMetric m,Map<String,Long> breakdown,String anomalyWarning) {
        var e=executions.findById(id).orElseThrow(); apply(e,m);
        e.errorBreakdown=encodeBreakdown(breakdown);
        if(anomalyWarning!=null) e.anomalyWarning=anomalyWarning;
        executions.save(e); metrics.save(m);
        return new ExecutionEvent(view(e),MetricView.of(m));
    }
    @Transactional public ExecutionEvent finish(UUID id, TestMetric m, Map<String,Long> breakdown, String anomalyWarning, ExecutionStatus status, String failure) {
        return finish(id, m, breakdown, anomalyWarning, status, failure, null);
    }
    @Transactional public ExecutionEvent finish(UUID id, TestMetric m, Map<String,Long> breakdown, String anomalyWarning, ExecutionStatus status, String failure, String stopReason) {
        var e=executions.findById(id).orElseThrow();
        e.status=status; e.endTime=Instant.now(); e.failureReason=failure; apply(e,m);
        e.errorBreakdown=encodeBreakdown(breakdown);
        if(anomalyWarning!=null) e.anomalyWarning=anomalyWarning;
        e.stopReason=stopReason;
        e.throughput=m.totalRequests/Math.max(0.001,m.elapsedSeconds);
        executions.save(e); metrics.save(m);
        var report=new TestReport(); report.executionId=id;
        report.assessment=status==ExecutionStatus.FAILED ? "ENGINE_FAILED" : m.totalRequests==0 ? "NO_SAMPLES" : m.errorRate>=5 ? "HIGH_ERROR_RATE" : m.p95LatencyMs>=1000 ? "HIGH_LATENCY" : "WITHIN_DEFAULT_THRESHOLDS";
        report.summary=String.format(Locale.ROOT,"%s · %d requests · %.2f%% errors · p95 %.0f ms · p99 %.0f ms. Đánh giá theo ngưỡng cố định (error ≥ 5%%, p95 ≥ 1000 ms); không phải chẩn đoán AI.", status,m.totalRequests,m.errorRate,m.p95LatencyMs,m.p99LatencyMs);
        reports.save(report); return new ExecutionEvent(view(e),MetricView.of(m));
    }
    private void apply(TestExecution e,TestMetric m) {
        e.totalRequests=m.totalRequests; e.failedRequests=m.failedRequests; e.avgLatencyMs=m.avgLatencyMs;
        e.p50LatencyMs=m.p50LatencyMs; e.p95LatencyMs=m.p95LatencyMs;
        e.p99LatencyMs=m.p99LatencyMs; e.maxLatencyMs=m.maxLatencyMs; e.throughput=m.throughput;
    }
    private ConfigRequest snapshot(TestExecution e) {
        ConfigRequest c;
        try { c=json.readValue(e.configSnapshot,ConfigRequest.class); if(c.name()==null) throw new IllegalStateException(); }
        catch(Exception ex) {
            if(e.testConfigId!=null) c=configs.request(configs.owned(e.testConfigId,e.userId));
            else c=new ConfigRequest("Cấu hình đã xóa","","GET",Map.of(),"",1,1,1,5000,"CONSTANT",0,List.of());
        }
        return c;
    }
    public ExecutionView view(TestExecution e) {
        ConfigRequest c=snapshot(e);
        return new ExecutionView(e.id,e.testConfigId,c.name(),c.targetUrl(),c.httpMethod(),c.virtualUsers(),c.durationSeconds(),c.requestsPerSecond(),
            e.status,e.startTime,e.endTime,e.createdAt,e.failureReason,e.totalRequests,e.failedRequests,e.avgLatencyMs,
            e.p50LatencyMs,e.p95LatencyMs,e.throughput,e.totalRequests==0 ? 0 : e.failedRequests*100.0/e.totalRequests,
            e.mockDataSetId,decodeBreakdown(e.errorBreakdown),e.anomalyWarning,
            c.resolvedProfile(),e.p99LatencyMs,e.maxLatencyMs,e.stopReason);
    }
    private String encodeBreakdown(Map<String,Long> breakdown) {
        if(breakdown==null || breakdown.isEmpty()) return "{}";
        try { return json.writeValueAsString(breakdown); }
        catch(Exception ex) { return "{}"; }
    }
    private Map<String,Long> decodeBreakdown(String raw) {
        if(raw==null || raw.isBlank()) return Map.of();
        try { return json.readValue(raw, new TypeReference<Map<String,Long>>() {}); }
        catch(Exception ex) { return Map.of(); }
    }
    public List<MetricView> metrics(UUID id,UUID owner) {
        owned(id,owner); return metrics.findByExecutionIdOrderBySampleTimeAsc(id).stream().map(MetricView::of).toList();
    }
    public List<MetricView> metricsAfter(UUID id,UUID owner,Double afterElapsed) {
        if(afterElapsed==null) return metrics(id,owner);
        owned(id,owner);
        return metrics.findByExecutionIdAndElapsedSecondsGreaterThanOrderBySampleTimeAsc(id,afterElapsed)
            .stream().map(MetricView::of).toList();
    }
    public TestReport report(UUID id,UUID owner) {
        var e=owned(id,owner); if(!e.status.terminal()) throw ApiException.conflict("Báo cáo có sau khi kết thúc test");
        return reports.findByExecutionId(id).orElseThrow(ApiException::missing);
    }
    public AiAnalysisInput aiAnalysisInput(UUID id,UUID owner) {
        report(id,owner);
        var execution=owned(id,owner);
        return AiAnalysisInput.build(snapshot(execution), view(execution), metrics(id,owner));
    }
    @Transactional public void markAiPending(UUID id) {
        reports.findByExecutionId(id).ifPresent(r -> { r.aiStatus="PENDING"; reports.save(r); });
    }
    @Transactional public void markAiFailed(UUID id) {
        reports.findByExecutionId(id).ifPresent(r -> { r.aiStatus="FAILED"; reports.save(r); });
    }
    @Transactional public TestReport saveAiAnalysis(UUID id, UUID owner, String analysis) {
        var result=report(id,owner); result.aiAnalysis=analysis; result.aiAnalyzedAt=Instant.now();
        String status="COMPLETED";
        try {
            var node=json.readTree(analysis);
            String mode=node.path("analysisMode").asText("");
            if("insufficient_data".equalsIgnoreCase(mode)) status="INSUFFICIENT_DATA";
            else if("model".equalsIgnoreCase(mode)) status="COMPLETED";
            String model=node.path("model").asText(null);
            if(model!=null && !model.isBlank()) result.aiModel=model.length()>120 ? model.substring(0,120) : model;
        } catch(Exception ignored) {}
        result.aiStatus=status;
        return reports.save(result);
    }
    @Transactional public void deleteExecution(UUID id, UUID owner) {
        var e=owned(id,owner);
        if(!e.status.terminal()) throw ApiException.conflict("Không thể xóa bài kiểm thử đang chạy; hãy dừng trước khi xóa");
        reports.deleteByExecutionId(id);
        metrics.deleteByExecutionId(id);
        executions.delete(e);
    }
    @Transactional public int clearHistory(UUID owner) {
        var finished=executions.findByUserIdAndStatusIn(owner, List.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.STOPPED));
        if(finished.isEmpty()) return 0;
        var ids=finished.stream().map(e -> e.id).toList();
        reports.deleteByExecutionIdIn(ids);
        metrics.deleteByExecutionIdIn(ids);
        executions.deleteAll(finished);
        return finished.size();
    }
    @Transactional public void recoverInterrupted() {
        for(var e:executions.findByStatusIn(List.of(ExecutionStatus.PENDING,ExecutionStatus.RUNNING))) {
            e.status=ExecutionStatus.FAILED; e.endTime=Instant.now(); e.failureReason="Engine khởi động lại trước khi test hoàn thành";
            executions.save(e);
            if(reports.findByExecutionId(e.id).isEmpty()) {
                var r=new TestReport(); r.executionId=e.id; r.assessment="ENGINE_INTERRUPTED"; r.summary=e.failureReason; reports.save(r);
            }
        }
    }
}
