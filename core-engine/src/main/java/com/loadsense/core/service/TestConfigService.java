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
        if(executions.existsByTestConfigId(id)) throw ApiException.conflict("Cấu hình có lịch sử chạy; giữ lại để bảo toàn báo cáo");
        datasets.deleteAll(datasets.findByTestConfigIdOrderByCreatedAtDesc(id)); configs.delete(config);
    }
    private ConfigView save(TestConfig c, ConfigRequest input) {
        policy.validate(input);
        c.name=input.name().trim(); c.targetUrl=input.targetUrl(); c.httpMethod=input.httpMethod();
        c.requestHeaders=encode(input.headers()==null ? Map.of() : input.headers()); c.requestBody=input.body();
        c.virtualUsers=input.virtualUsers(); c.durationSeconds=input.durationSeconds();
        c.requestsPerSecond=input.requestsPerSecond(); c.timeoutMs=input.timeoutMs(); c.updatedAt=Instant.now();
        return view(configs.save(c));
    }
    public ConfigView view(TestConfig c) {
        return new ConfigView(c.id,c.name,c.targetUrl,c.httpMethod,headers(c),c.requestBody,c.virtualUsers,
            c.durationSeconds,c.requestsPerSecond,c.timeoutMs,c.createdAt,c.updatedAt);
    }
    public ConfigRequest request(TestConfig c) {
        return new ConfigRequest(c.name,c.targetUrl,c.httpMethod,headers(c),c.requestBody,c.virtualUsers,c.durationSeconds,c.requestsPerSecond,c.timeoutMs);
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
        if(Set.of("GET","HEAD").contains(c.httpMethod)) throw ApiException.invalid("Dữ liệu mock chỉ dành cho method có body");
        var payloads=parsePayloads(input.payloadsJson());
        if(input.schemaJson()!=null && !input.schemaJson().isBlank()) {
            try { if(!json.readTree(input.schemaJson()).isObject()) throw new IllegalArgumentException(); }
            catch(Exception e) { throw ApiException.invalid("Schema phải là JSON object (chỉ lưu tham chiếu, chưa có AI/schema validator)"); }
        }
        var data=new MockDataSet(); data.testConfigId=configId; data.name=input.name().trim();
        data.schemaJson=input.schemaJson(); data.payloadsJson=encode(payloads); return datasets.save(data);
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
