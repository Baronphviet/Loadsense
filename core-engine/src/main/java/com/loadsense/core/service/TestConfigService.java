package com.loadsense.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.*;
import com.loadsense.core.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

@Service
public class TestConfigService {
    private final TestConfigRepository configs;
    private final TestExecutionRepository executions;
    private final MockDataSetRepository datasets;
    private final TargetPolicy policy;
    private final ObjectMapper json;
    public TestConfigService(TestConfigRepository configs, TestExecutionRepository executions,
        MockDataSetRepository datasets, TargetPolicy policy, ObjectMapper json) {
        this.configs=configs; this.executions=executions; this.datasets=datasets; this.policy=policy; this.json=json;
    }
    public TestConfig owned(UUID id, UUID userId) { return configs.findByIdAndUserId(id,userId).orElseThrow(ApiException::missing); }
    public List<ConfigView> list(UUID owner) { return configs.findByUserIdOrderByUpdatedAtDesc(owner).stream().map(this::view).toList(); }
    public ConfigView create(UUID owner, ConfigRequest input) {
        var config=new TestConfig(); config.userId=owner; return save(config,input);
    }
    public ConfigView update(UUID id, UUID owner, ConfigRequest input) { return save(owned(id,owner),input); }
    @Transactional public void delete(UUID id, UUID owner) {
        var config=owned(id,owner);
        if(executions.existsByTestConfigIdAndStatusIn(id, List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING)))
            throw ApiException.conflict("Bài kiểm thử đang chạy; hãy dừng trước khi xóa cấu hình");
        var linkedExecutions=executions.findByTestConfigId(id);
        for(var e:linkedExecutions) e.testConfigId=null;
        var dsList=datasets.findByTestConfigIdOrderByCreatedAtDesc(id);
        if(!dsList.isEmpty()) {
            var dsIds=dsList.stream().map(d -> d.id).toList();
            for(var e:executions.findByMockDataSetIdIn(dsIds)) e.mockDataSetId=null;
        }
        executions.flush();
        datasets.deleteAll(dsList);
        configs.delete(config);
    }
    private ConfigView save(TestConfig c, ConfigRequest input) {
        policy.validate(input,c.userId);
        c.name=input.name().trim(); c.targetUrl=input.targetUrl(); c.httpMethod=input.httpMethod();
        c.requestHeaders=encode(input.headers()==null ? Map.of() : input.headers()); c.requestBody=input.body();
        c.virtualUsers=input.virtualUsers(); c.durationSeconds=input.durationSeconds();
        c.requestsPerSecond=input.requestsPerSecond(); c.timeoutMs=input.timeoutMs();
        c.loadProfile=input.resolvedProfile();
        c.rampUpSeconds="RAMP_UP".equals(c.loadProfile) ? Math.min(c.durationSeconds, input.resolvedRampUpSeconds()) : 0;
        var steps=input.resolvedSteps();
        c.stepsJson=steps.isEmpty() ? null : encode(steps);
        c.updatedAt=Instant.now();
        return view(configs.save(c));
    }
    public ConfigView view(TestConfig c) {
        return new ConfigView(c.id,c.name,c.targetUrl,c.httpMethod,headers(c),c.requestBody,c.virtualUsers,
            c.durationSeconds,c.requestsPerSecond,c.timeoutMs,
            c.loadProfile==null || c.loadProfile.isBlank() ? "CONSTANT" : c.loadProfile,
            c.rampUpSeconds,steps(c),c.createdAt,c.updatedAt);
    }
    public ConfigRequest request(TestConfig c) {
        return new ConfigRequest(c.name,c.targetUrl,c.httpMethod,headers(c),c.requestBody,c.virtualUsers,
            c.durationSeconds,c.requestsPerSecond,c.timeoutMs,
            c.loadProfile==null || c.loadProfile.isBlank() ? "CONSTANT" : c.loadProfile,
            c.rampUpSeconds,steps(c));
    }
    private List<ScenarioStep> steps(TestConfig c) {
        if(c.stepsJson==null || c.stepsJson.isBlank()) return List.of();
        try { return json.readValue(c.stepsJson,new TypeReference<List<ScenarioStep>>() {}); }
        catch(Exception e) { return List.of(); }
    }
    private Map<String,String> headers(TestConfig c) {
        try { return json.readValue(c.requestHeaders,new TypeReference<Map<String,String>>() {}); }
        catch(Exception e) { throw ApiException.invalid("Headers lưu trữ không hợp lệ"); }
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw ApiException.invalid("Không thể chuẩn hóa JSON"); }
    }
    public List<MockDataSet> datasets(UUID configId, UUID owner) {
        owned(configId,owner); return datasets.findByTestConfigIdOrderByCreatedAtDesc(configId);
    }
    public MockDataSet addDataset(UUID configId, UUID owner, DataSetRequest input) {
        var c=owned(configId,owner);
        if(input.payloadsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144 ||
            (input.schemaJson()!=null && input.schemaJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536))
            throw ApiException.invalid("Payloads tối đa 256 KiB, schema tối đa 64 KiB");
        var stepList=steps(c);
        String bodyMethod=stepList.isEmpty() ? c.httpMethod : stepList.get(0).httpMethod();
        if(Set.of("GET","HEAD").contains(bodyMethod)) throw ApiException.invalid("Dữ liệu mock chỉ dành cho method có body");
        var payloads=parsePayloads(input.payloadsJson());
        if(input.schemaJson()!=null && !input.schemaJson().isBlank()) {
            try {
                var schema=json.readTree(input.schemaJson());
                if(!schema.isObject()) throw ApiException.invalid("Schema phải là JSON object");
                rejectExternalReferences(schema);
                var validator=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
                for(var payload:payloads) if(!validator.validate(payload).isEmpty()) throw ApiException.invalid("Payload không khớp JSON Schema");
            } catch(ApiException e) { throw e; }
            catch(Exception e) { throw ApiException.invalid("Schema hoặc tham chiếu không hợp lệ"); }
        }
        var data=new MockDataSet(); data.testConfigId=configId; data.name=input.name().trim();
        data.schemaJson=input.schemaJson(); data.payloadsJson=encode(payloads); return datasets.save(data);
    }
    private void rejectExternalReferences(JsonNode node) {
        if(node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if(Set.of("$ref","$dynamicRef","$recursiveRef").contains(entry.getKey()) && entry.getValue().isTextual() && !entry.getValue().asText().startsWith("#"))
                    throw ApiException.invalid("Chỉ hỗ trợ tham chiếu JSON Schema nội bộ");
                if(entry.getKey().equals("$schema") && (!entry.getValue().isTextual() || !Set.of(
                    "http://json-schema.org/draft-04/schema#", "http://json-schema.org/draft-06/schema#", "http://json-schema.org/draft-07/schema#",
                    "https://json-schema.org/draft/2019-09/schema", "https://json-schema.org/draft/2020-12/schema").contains(entry.getValue().asText())))
                    throw ApiException.invalid("Phiên bản JSON Schema không được hỗ trợ");
                rejectExternalReferences(entry.getValue());
            });
        } else if(node.isArray()) node.forEach(this::rejectExternalReferences);
    }
    public List<String> payloads(UUID configId, UUID dataId) {
        if(dataId==null) return List.of();
        var data=datasets.findById(dataId).filter(d -> d.testConfigId.equals(configId)).orElseThrow(ApiException::missing);
        return parsePayloads(data.payloadsJson).stream().map(this::encode).toList();
    }
    private List<JsonNode> parsePayloads(String text) {
        try {
            var node=json.readTree(text);
            if(!node.isArray() || node.size()<1 || node.size()>1000) throw ApiException.invalid("Payloads phải là mảng JSON có 1–1000 phần tử");
            List<JsonNode> result=new ArrayList<>();
            for(var item:node) {
                if(!item.isObject() || encode(item).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)
                    throw ApiException.invalid("Mỗi payload phải là object, tối đa 64 KiB");
                result.add(item);
            }
            return result;
        } catch(ApiException e) { throw e; } catch(Exception e) { throw ApiException.invalid("JSON payload không hợp lệ"); }
    }
}
