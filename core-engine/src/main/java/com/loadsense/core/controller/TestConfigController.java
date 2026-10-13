package com.loadsense.core.controller;
import com.loadsense.core.service.*;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.MockDataSet;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import java.util.*;

@RestController @RequestMapping("/api/test-configs")
public class TestConfigController {
    private final TestConfigService service;
    public TestConfigController(TestConfigService service) { this.service=service; }
    @GetMapping List<ConfigView> list(@AuthenticationPrincipal Jwt jwt) { return service.list(UUID.fromString(jwt.getSubject())); }
    @GetMapping("/{id}") ConfigView get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return service.view(service.owned(id,UUID.fromString(jwt.getSubject()))); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ConfigView create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ConfigRequest input) { return service.create(UUID.fromString(jwt.getSubject()),input); }
    @PutMapping("/{id}") ConfigView update(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ConfigRequest input) { return service.update(id,UUID.fromString(jwt.getSubject()),input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { service.delete(id,UUID.fromString(jwt.getSubject())); }
    @GetMapping("/{id}/mock-data") List<MockDataSet> datasets(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return service.datasets(id,UUID.fromString(jwt.getSubject())); }
    @PostMapping("/{id}/mock-data") @ResponseStatus(HttpStatus.CREATED)
    MockDataSet dataset(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody DataSetRequest input) { return service.addDataset(id,UUID.fromString(jwt.getSubject()),input); }
}
