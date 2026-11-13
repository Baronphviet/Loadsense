package com.loadsense.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.ExecutionStatus;
import com.loadsense.core.service.AiAnalysisInput;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisInputTest {
    @Test void analysisContainsBoundedSamplesAndLoadSettingsWithoutSecrets() throws Exception {
        var config = new ConfigRequest("private-name", "https://secret.example/private", "POST", Map.of("Authorization", "secret-token"), "secret-body", 8, 600, 15, 5000, "RAMP_UP", 60, List.of());
        var execution = new ExecutionView(UUID.randomUUID(), UUID.randomUUID(), "private-name", "https://secret.example/private", "POST", 8, 600, 15, ExecutionStatus.STOPPED, null, null, Instant.now(), "secret-failure", 100, 2, 250, 200, 500, 5, 2, null, Map.of("http5xx", 2L), "Early anomaly detected", "RAMP_UP", 650, 800, "ANOMALY");
        List<MetricView> samples = new ArrayList<>();
        for(int i=0; i<100; i++) samples.add(new MetricView(UUID.randomUUID(), Instant.now(), i, i, 0, 5, 200, 100, 400, 0, 8, 450, 500, 390, 0));
        var result = new ObjectMapper().valueToTree(AiAnalysisInput.build(config, execution, samples));
        assertThat(result.path("status").asText()).isEqualTo("STOPPED");
        assertThat(result.path("stopReason").asText()).isEqualTo("ANOMALY");
        assertThat(result.path("p99LatencyMs").asDouble()).isEqualTo(650);
        assertThat(result.path("maxLatencyMs").asDouble()).isEqualTo(800);
        assertThat(result.path("loadConfig").path("loadProfile").asText()).isEqualTo("RAMP_UP");
        assertThat(result.path("loadConfig").path("rampUpSeconds").asInt()).isEqualTo(60);
        assertThat(result.path("loadConfig").path("timeoutMs").asInt()).isEqualTo(5000);
        assertThat(result.path("loadConfig").path("virtualUsers").asInt()).isEqualTo(8);
        assertThat(result.path("totalRequests").asInt()).isEqualTo(100);
        assertThat(result.path("errorBreakdown").path("http5xx").asLong()).isEqualTo(2L);
        assertThat(result.path("anomalyWarning").asText()).isEqualTo("Early anomaly detected");
        assertThat(result.path("samples").size()).isEqualTo(60);
        assertThat(result.path("samples").get(0).path("intervalP95Ms").asDouble()).isEqualTo(390);
        assertThat(result.path("samples").get(0).path("elapsedSeconds").asDouble()).isEqualTo(0);
        assertThat(result.path("samples").get(59).path("elapsedSeconds").asDouble()).isEqualTo(99);
        assertThat(result.toString()).doesNotContain("secret", "private", "Authorization", "sampleTime", "id");
    }

    @Test void analysisSupportsNoSamplesWithoutInventingMeasurements() {
        var config = new ConfigRequest("test", "https://example.test", "GET", Map.of(), "", 1, 1, 1, 1000);
        var execution = new ExecutionView(UUID.randomUUID(), UUID.randomUUID(), "test", "https://example.test", "GET", 1, 1, 1, ExecutionStatus.FAILED, null, null, Instant.now(), null, 0, 0, 0, 0, 0, 0, 0, null, Map.of(), null);
        var result = new ObjectMapper().valueToTree(AiAnalysisInput.build(config, execution, List.of()));
        assertThat(result.path("samples").size()).isZero();
        assertThat(result.path("totalRequests").asInt()).isZero();
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
    }
}
