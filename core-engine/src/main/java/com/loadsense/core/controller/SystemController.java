package com.loadsense.core.controller;

import com.loadsense.core.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.util.*;
import com.loadsense.core.client.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.loadsense.core.dto.ApiDtos.ApproveHostRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;

@RestController
public class SystemController {
    private final TargetPolicy policy; private final LoadEngine engine; private final boolean demoEnabled; private final AiClient ai;
    public SystemController(TargetPolicy policy,LoadEngine engine,AiClient ai,@Value("${loadsense.demo-enabled}") boolean demoEnabled) {
        this.policy=policy; this.engine=engine; this.demoEnabled=demoEnabled; this.ai=ai;
    }
    @GetMapping("/api/health") Map<String,Object> health() { return Map.of("status","UP","aiStatus",ai.configured()?"CONFIGURED":"NOT_CONFIGURED"); }
    @GetMapping("/api/capabilities") Map<String,Object> capabilities(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("aiEnabled",ai.available(),"allowedTargetHosts",policy.allowedHosts(UUID.fromString(jwt.getSubject())),"maxVirtualUsers",300,
            "maxDurationSeconds",600,"maxRequestsPerSecond",500,"activeExecutions",engine.activeCount(),"demoEnabled",demoEnabled);
    }
    @PostMapping("/api/target-hosts") Map<String,Object> approveHost(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ApproveHostRequest input) {
        policy.approve(UUID.fromString(jwt.getSubject()),input.host()); return Map.of("approved",true);
    }
    @PostMapping("/api/ai/parse-prompt") JsonNode parsePrompt(@RequestBody JsonNode input) { return ai.call("/parse-prompt",input); }
    @PostMapping("/api/ai/generate-mockdata") JsonNode generateMock(@RequestBody JsonNode input) { return ai.call("/generate-mockdata",input); }
    @PostMapping("/api/ai/analyze-log") JsonNode analyzeMetrics(@RequestBody JsonNode input) { return ai.call("/analyze-log",input); }
    @RequestMapping(value="/demo/echo",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.HEAD,RequestMethod.OPTIONS})
    ResponseEntity<Map<String,Object>> demo(@RequestParam(defaultValue="30") int delayMs,@RequestParam(defaultValue="200") int status) throws InterruptedException {
        if(!demoEnabled) throw ApiException.missing();
        if(delayMs<0 || delayMs>1000 || !Set.of(200,201,400,500).contains(status)) throw ApiException.invalid("Demo delay 0–1000 ms, status 200/201/400/500");
        Thread.sleep(delayMs); return ResponseEntity.status(status).body(Map.of("service","LoadSense demo API","delayMs",delayMs));
    }
}
