package com.loadsense.core.controller;

import com.loadsense.core.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.util.*;

@RestController
public class SystemController {
    private final TargetPolicy policy; private final LoadEngine engine; private final boolean demoEnabled;
    public SystemController(TargetPolicy policy,LoadEngine engine,@Value("${loadsense.demo-enabled}") boolean demoEnabled) {
        this.policy=policy; this.engine=engine; this.demoEnabled=demoEnabled;
    }
    @GetMapping("/api/health") Map<String,Object> health() { return Map.of("status","UP","aiStatus","DEFERRED"); }
    @GetMapping("/api/capabilities") Map<String,Object> capabilities() {
        return Map.of("aiEnabled",false,"allowedTargetHosts",policy.allowedHosts(),"maxVirtualUsers",300,
            "maxDurationSeconds",600,"maxRequestsPerSecond",500,"activeExecutions",engine.activeCount(),"demoEnabled",demoEnabled);
    }
    @PostMapping({"/api/ai/parse-prompt","/api/ai/generate-mockdata","/api/ai/analyze-log"})
    void ai() { throw new ApiException(HttpStatus.NOT_IMPLEMENTED,"AI_DEFERRED","LocalAI chưa được triển khai. Dùng cấu hình và dữ liệu thủ công."); }
    @RequestMapping(value="/demo/echo",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.HEAD,RequestMethod.OPTIONS})
    ResponseEntity<Map<String,Object>> demo(@RequestParam(defaultValue="30") int delayMs,@RequestParam(defaultValue="200") int status) throws InterruptedException {
        if(!demoEnabled) throw ApiException.missing();
        if(delayMs<0 || delayMs>1000 || !Set.of(200,201,400,500).contains(status)) throw ApiException.invalid("Demo delay 0–1000 ms, status 200/201/400/500");
        Thread.sleep(delayMs); return ResponseEntity.status(status).body(Map.of("service","LoadSense demo API","delayMs",delayMs));
    }
}
