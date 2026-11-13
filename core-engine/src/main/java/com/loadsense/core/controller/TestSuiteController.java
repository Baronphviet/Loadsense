package com.loadsense.core.controller;

import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.service.RateLimiter;
import com.loadsense.core.service.TestSuiteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** REST boundary cho phân hệ Auto Test Suite (crawl, lập kế hoạch Form/YAML và chạy hàng loạt). */
@RestController
@RequestMapping("/api/suites")
public class TestSuiteController {
    private final TestSuiteService suites;
    private final RateLimiter suiteLimiter = new RateLimiter(30, 60);

    public TestSuiteController(TestSuiteService suites) {
        this.suites = suites;
    }

    @GetMapping
    public List<TestSuiteSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return suites.list(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestSuiteView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateSuiteRequest input) {
        suiteLimiter.checkOrThrow(jwt.getSubject(), "SUITE_RATE_LIMITED", "Thao tác tạo Test Suite quá nhanh; hãy thử lại sau");
        return suites.createAndGenerate(UUID.fromString(jwt.getSubject()), input);
    }

    @GetMapping("/{id}")
    public TestSuiteView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return suites.get(UUID.fromString(jwt.getSubject()), id);
    }

    @PutMapping("/{id}/plan")
    public TestSuiteView updatePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody UpdateSuitePlanRequest input) {
        return suites.updatePlan(UUID.fromString(jwt.getSubject()), id, input);
    }

    @PostMapping("/{id}/run")
    public TestSuiteView run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @RequestBody(required = false) SuiteRunRequest input) {
        suiteLimiter.checkOrThrow(jwt.getSubject(), "SUITE_RATE_LIMITED", "Thao tác chạy Suite quá nhanh; hãy thử lại sau");
        return suites.runSuite(UUID.fromString(jwt.getSubject()), id, input);
    }

    @PostMapping("/{id}/cases/{caseId}/ai-explain")
    public TestSuiteView explainCase(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @PathVariable String caseId) {
        suiteLimiter.checkOrThrow(jwt.getSubject(), "SUITE_RATE_LIMITED", "Thao tác gọi AI quá nhanh; hãy thử lại sau");
        return suites.explainCase(UUID.fromString(jwt.getSubject()), id, caseId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        suites.delete(UUID.fromString(jwt.getSubject()), id);
    }
}
