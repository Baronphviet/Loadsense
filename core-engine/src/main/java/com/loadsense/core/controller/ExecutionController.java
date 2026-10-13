package com.loadsense.core.controller;
import com.loadsense.core.service.*;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.TestReport;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import jakarta.validation.Valid;
import java.util.*;

@RestController @RequestMapping("/api")
public class ExecutionController {
    private final LoadEngine engine; private final ExecutionStore store;
    public ExecutionController(LoadEngine engine,ExecutionStore store) { this.engine=engine; this.store=store; }
    @PostMapping("/test-configs/{id}/executions") @ResponseStatus(HttpStatus.ACCEPTED)
    ExecutionView start(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody StartRequest input) { return engine.start(id,UUID.fromString(jwt.getSubject()),input); }
    @GetMapping("/executions") List<ExecutionView> list(@AuthenticationPrincipal Jwt jwt) { return store.list(UUID.fromString(jwt.getSubject())); }
    @GetMapping("/executions/{id}") ExecutionView get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.get(id,UUID.fromString(jwt.getSubject())); }
    @PostMapping("/executions/{id}/stop") ExecutionView stop(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return engine.stop(id,UUID.fromString(jwt.getSubject())); }
    @GetMapping("/executions/{id}/metrics") List<MetricView> metrics(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.metrics(id,UUID.fromString(jwt.getSubject())); }
    @GetMapping("/executions/{id}/report") TestReport report(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.report(id,UUID.fromString(jwt.getSubject())); }
    @GetMapping(value="/executions/{id}/metrics.csv",produces="text/csv")
    ResponseEntity<String> csv(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        var rows=store.metrics(id,UUID.fromString(jwt.getSubject()));
        var csv=new StringBuilder("sample_time,elapsed_seconds,total_requests,failed_requests,throughput,avg_latency_ms,p50_latency_ms,p95_latency_ms,error_rate,active_users\n");
        for(var m:rows) csv.append(String.format(Locale.ROOT,"%s,%.3f,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",m.sampleTime(),m.elapsedSeconds(),m.totalRequests(),m.failedRequests(),m.throughput(),m.avgLatencyMs(),m.p50LatencyMs(),m.p95LatencyMs(),m.errorRate(),m.activeUsers()));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=loadsense-"+id+".csv").body(csv.toString());
    }
}
