package com.loadsense.core.controller;

import com.loadsense.core.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.util.*;
import com.loadsense.core.client.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.loadsense.core.dto.ApiDtos.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;

@RestController
public class SystemController {
    private final TargetPolicy policy; private final LoadEngine engine; private final boolean demoEnabled;
    private final AiClient ai; private final AuthService auth; private final RateLimiter aiLimiter;
    private final RateLimiter probeLimiter; private final AutoDiscoveryService discovery;
    public SystemController(TargetPolicy policy,LoadEngine engine,AiClient ai,AuthService auth,AutoDiscoveryService discovery,
        @Value("${loadsense.demo-enabled}") boolean demoEnabled,
        @Value("${loadsense.ai-rate-limit-per-minute:10}") int aiRateLimitPerMinute) {
        this.policy=policy; this.engine=engine; this.demoEnabled=demoEnabled; this.ai=ai; this.auth=auth; this.discovery=discovery;
        this.aiLimiter=new RateLimiter(aiRateLimitPerMinute,60);
        this.probeLimiter=new RateLimiter(20,60);
    }
    @GetMapping("/api/health") Map<String,Object> health() { return Map.of("status","UP","aiStatus",ai.configured()?"CONFIGURED":"NOT_CONFIGURED"); }
    @GetMapping("/api/capabilities") Map<String,Object> capabilities(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("aiEnabled",ai.available(),"allowedTargetHosts",policy.allowedHosts(UUID.fromString(jwt.getSubject())),"maxVirtualUsers",300,
            "maxDurationSeconds",600,"maxRequestsPerSecond",500,"activeExecutions",engine.activeCount(),"demoEnabled",demoEnabled);
    }
    @PostMapping("/api/target-hosts") Map<String,Object> approveHost(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ApproveHostRequest input) {
        policy.approve(UUID.fromString(jwt.getSubject()),input.host()); return Map.of("approved",true);
    }
    @GetMapping("/api/settings") UserSettingsDto getSettings(@AuthenticationPrincipal Jwt jwt) {
        return auth.getSettings(UUID.fromString(jwt.getSubject()));
    }
    @PutMapping("/api/settings") UserSettingsDto updateSettings(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody UserSettingsDto input) {
        return auth.updateSettings(UUID.fromString(jwt.getSubject()),input);
    }
    @PostMapping("/api/probe") ProbeResponse probe(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ProbeRequest input) {
        probeLimiter.checkOrThrow(jwt.getSubject(),"PROBE_RATE_LIMITED","Vượt quá giới hạn thăm dò URL mỗi phút; hãy thử lại sau");
        return discovery.probe(UUID.fromString(jwt.getSubject()),input);
    }
    @PostMapping("/api/discover") DiscoverResponse discover(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody DiscoverRequest input) {
        probeLimiter.checkOrThrow(jwt.getSubject(),"PROBE_RATE_LIMITED","Vượt quá giới hạn khám phá URL mỗi phút; hãy thử lại sau");
        return discovery.discover(UUID.fromString(jwt.getSubject()),input);
    }
    @PostMapping("/api/ai/parse-prompt") JsonNode parsePrompt(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode input) {
        aiLimiter.checkOrThrow(jwt.getSubject(),"AI_RATE_LIMITED","Vượt quá giới hạn gọi AI mỗi phút; hãy thử lại sau");
        return ai.call("/parse-prompt",input);
    }
    @PostMapping("/api/ai/generate-mockdata") JsonNode generateMock(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode input) {
        aiLimiter.checkOrThrow(jwt.getSubject(),"AI_RATE_LIMITED","Vượt quá giới hạn gọi AI mỗi phút; hãy thử lại sau");
        return ai.call("/generate-mockdata",input);
    }
    @PostMapping("/api/ai/analyze-log") JsonNode analyzeMetrics(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode input) {
        aiLimiter.checkOrThrow(jwt.getSubject(),"AI_RATE_LIMITED","Vượt quá giới hạn gọi AI mỗi phút; hãy thử lại sau");
        return ai.call("/analyze-log",input);
    }
    @RequestMapping(value="/demo/echo",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.HEAD,RequestMethod.OPTIONS})
    ResponseEntity<Map<String,Object>> demo(@RequestParam(defaultValue="30") int delayMs,@RequestParam(defaultValue="200") int status) throws InterruptedException {
        if(!demoEnabled) throw ApiException.missing();
        if(delayMs<0 || delayMs>1000 || !Set.of(200,201,400,500).contains(status)) throw ApiException.invalid("Demo delay 0–1000 ms, status 200/201/400/500");
        Thread.sleep(delayMs); return ResponseEntity.status(status).body(Map.of("service","LoadSense demo API","delayMs",delayMs,"token","demo-token-01","itemId","demo-item-1"));
    }
}
