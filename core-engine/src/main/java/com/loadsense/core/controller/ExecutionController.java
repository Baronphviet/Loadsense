package com.loadsense.core.controller;
import com.loadsense.core.service.*;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.TestReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import jakarta.validation.Valid;
import java.util.*;
import com.loadsense.core.client.AiClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController @RequestMapping("/api")
public class ExecutionController {
    private final LoadEngine engine; private final ExecutionStore store; private final AiClient ai;
    private final ObjectMapper json; private final RateLimiter aiLimiter;
    public ExecutionController(LoadEngine engine,ExecutionStore store,AiClient ai,ObjectMapper json,
        @Value("${loadsense.ai-rate-limit-per-minute:10}") int aiRateLimitPerMinute) {
        this.engine=engine; this.store=store; this.ai=ai; this.json=json;
        this.aiLimiter=new RateLimiter(aiRateLimitPerMinute,60);
    }
    @PostMapping("/test-configs/{id}/executions") @ResponseStatus(HttpStatus.ACCEPTED)
    ExecutionView start(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody StartRequest input) { return engine.start(id,UUID.fromString(jwt.getSubject()),input); }
    @GetMapping("/executions") List<ExecutionView> list(@AuthenticationPrincipal Jwt jwt) { return store.list(UUID.fromString(jwt.getSubject())); }
    @GetMapping("/executions/{id}") ExecutionView get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.get(id,UUID.fromString(jwt.getSubject())); }
    @PostMapping("/executions/{id}/stop") ExecutionView stop(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return engine.stop(id,UUID.fromString(jwt.getSubject())); }
    @DeleteMapping("/executions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { store.deleteExecution(id,UUID.fromString(jwt.getSubject())); }
    @DeleteMapping("/executions")
    Map<String,Integer> clearAll(@AuthenticationPrincipal Jwt jwt) { return Map.of("deleted", store.clearHistory(UUID.fromString(jwt.getSubject()))); }
    @GetMapping("/executions/{id}/metrics") List<MetricView> metrics(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Double afterElapsed) {
        return store.metricsAfter(id,UUID.fromString(jwt.getSubject()),afterElapsed);
    }
    @GetMapping("/executions/{id}/report") TestReport report(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.report(id,UUID.fromString(jwt.getSubject())); }
    @PostMapping("/executions/{id}/report/ai") TestReport analyze(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) throws Exception {
        aiLimiter.checkOrThrow(jwt.getSubject(),"AI_RATE_LIMITED","Vượt quá giới hạn gọi AI mỗi phút; hãy thử lại sau");
        var owner=UUID.fromString(jwt.getSubject());
        var input=store.aiAnalysisInput(id,owner);
        return store.saveAiAnalysis(id,owner,json.writeValueAsString(ai.call("/analyze-log",input)));
    }
    @GetMapping(value="/executions/{id}/metrics.csv",produces="text/csv")
    ResponseEntity<String> csv(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        var rows=store.metrics(id,UUID.fromString(jwt.getSubject()));
        var csv=new StringBuilder("sample_time,elapsed_seconds,total_requests,failed_requests,throughput,avg_latency_ms,p50_latency_ms,p95_latency_ms,p99_latency_ms,max_latency_ms,interval_p95_ms,interval_error_rate,error_rate,active_users\n");
        for(var m:rows) csv.append(String.format(Locale.ROOT,"%s,%.3f,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",
            m.sampleTime(),m.elapsedSeconds(),m.totalRequests(),m.failedRequests(),m.throughput(),m.avgLatencyMs(),
            m.p50LatencyMs(),m.p95LatencyMs(),m.p99LatencyMs(),m.maxLatencyMs(),m.intervalP95Ms(),m.intervalErrorRate(),m.errorRate(),m.activeUsers()));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=loadsense-"+id+".csv").body(csv.toString());
    }
}
