package com.loadsense.core;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @ActiveProfiles("test")
class CoreFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Autowired com.loadsense.core.service.ExecutionStore store;
    @Autowired com.loadsense.core.service.TestConfigService configs;
    static HttpServer target;
    static final Queue<String> bodies=new ConcurrentLinkedQueue<>();
    static ExecutorService targetWorkers;
    @BeforeAll static void target() throws Exception {
        target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        targetWorkers=Executors.newFixedThreadPool(32); target.setExecutor(targetWorkers);
        target.createContext("/ok",exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes="{}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        target.createContext("/error",exchange -> { exchange.sendResponseHeaders(500,0); exchange.close(); });
        target.createContext("/slow",exchange -> {
            try { Thread.sleep(500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(200,0); exchange.close(); } catch(Exception ignored) {}
        });
        target.createContext("/redirect",exchange -> { exchange.getResponseHeaders().set("Location","http://169.254.169.254/"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        target.start();
    }
    @AfterAll static void cleanup() { target.stop(0); targetWorkers.shutdownNow(); }
    String token() throws Exception { return register("u"+UUID.randomUUID().toString().replace("-", "").substring(0,15)); }
    String register(String user) throws Exception {
        return request("POST","/api/auth/register",null,Map.of("username",user,"password","test-password-123"),201).get("token").asText();
    }
    JsonNode request(String method,String path,String token,Object body,int status) throws Exception {
        var builder=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var result=mvc.perform(builder).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return result.isBlank() ? json.nullNode() : json.readTree(result);
    }
    Map<String,Object> config(String endpoint,int duration,int users) {
        Map<String,Object> input=new HashMap<>(); input.put("name","Integration API");
        input.put("targetUrl","http://127.0.0.1:"+target.getAddress().getPort()+endpoint);
        input.put("httpMethod","GET"); input.put("headers",Map.of()); input.put("body","");
        input.put("virtualUsers",users); input.put("durationSeconds",duration); input.put("requestsPerSecond",30); input.put("timeoutMs",1000); return input;
    }
    String create(String token,Map<String,Object> config) throws Exception { return request("POST","/api/test-configs",token,config,201).get("id").asText(); }
    String start(String token,String config) throws Exception { return request("POST","/api/test-configs/"+config+"/executions",token,Map.of("authorizedTarget",true),202).get("id").asText(); }
    JsonNode awaitTerminal(String token,String execution) throws Exception {
        long deadline=System.nanoTime()+8_000_000_000L;
        while(System.nanoTime()<deadline) {
            var result=request("GET","/api/executions/"+execution,token,null,200);
            if(Set.of("COMPLETED","STOPPED","FAILED").contains(result.get("status").asText())) return result;
            Thread.sleep(100);
        }
        throw new AssertionError("Execution did not finish");
    }
    @Test void authenticationAndValidation() throws Exception {
        request("GET","/api/test-configs",null,null,401);
        String user="auth"+UUID.randomUUID().toString().substring(0,8); var token=register(user);
        request("POST","/api/auth/register",null,Map.of("username",user,"password","test-password-123"),409);
        request("POST","/api/auth/login",null,Map.of("username",user,"password","wrong-password"),401);
        request("POST","/api/auth/login",null,Map.of("username",user,"password","test-password-123"),200);
        var invalid=config("/ok",1,301); request("POST","/api/test-configs",token,invalid,400);
        invalid=config("/ok",1,1); invalid.put("targetUrl","http://169.254.169.254/latest/meta-data"); request("POST","/api/test-configs",token,invalid,400);
        invalid.put("targetUrl","http://localhost:0/"); request("POST","/api/test-configs",token,invalid,400);
        invalid=config("/ok",1,1); invalid.put("headers",Map.of("Host","example.com")); request("POST","/api/test-configs",token,invalid,400);
        invalid.put("headers",Map.of("X-Test","value\r\nInjected: yes")); request("POST","/api/test-configs",token,invalid,400);
    }
    @Test void completeRunSnapshotHistoryAndReport() throws Exception {
        String token=token(); String id=create(token,config("/ok",2,12));
        request("POST","/api/test-configs/"+id+"/executions",token,Map.of("authorizedTarget",false),400);
        String execution=start(token,id);
        var edited=config("/error",2,1); edited.put("name","Changed config"); request("PUT","/api/test-configs/"+id,token,edited,200);
        var completed=awaitTerminal(token,execution);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("name").asText()).isEqualTo("Integration API");
        assertThat(completed.get("totalRequests").asLong()).isBetween(1L,65L);
        assertThat(completed.get("errorRate").asDouble()).isZero();
        assertThat(request("GET","/api/executions/"+execution+"/metrics",token,null,200).size()).isGreaterThanOrEqualTo(1);
        assertThat(request("GET","/api/executions/"+execution+"/report",token,null,200).get("aiStatus").asText()).isEqualTo("DEFERRED");
        mvc.perform(get("/api/executions/"+execution+"/metrics.csv").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("p95_latency_ms")));
        request("DELETE","/api/test-configs/"+id,token,null,409);
    }
    @Test void stopIsIdempotentAndOwnershipIsEnforced() throws Exception {
        String owner=token(), outsider=token(); String id=create(owner,config("/slow",30,15)); String execution=start(owner,id);
        for(String suffix:List.of("","/metrics","/report","/metrics.csv")) request("GET","/api/executions/"+execution+suffix,outsider,null,404);
        request("POST","/api/executions/"+execution+"/stop",outsider,null,404);
        request("GET","/api/test-configs/"+id,outsider,null,404);
        request("GET","/api/test-configs/"+id+"/mock-data",outsider,null,404);
        request("POST","/api/executions/"+execution+"/stop",owner,null,200);
        request("POST","/api/executions/"+execution+"/stop",owner,null,200);
        assertThat(awaitTerminal(owner,execution).get("status").asText()).isEqualTo("STOPPED");
        request("POST","/api/executions/"+execution+"/stop",owner,null,200);
    }
    @Test void errorsTimeoutAndRedirectAreMeasured() throws Exception {
        String token=token();
        var error=awaitTerminal(token,start(token,create(token,config("/error",1,5))));
        assertThat(error.get("errorRate").asDouble()).isEqualTo(100);
        var slow=config("/slow",1,5); slow.put("timeoutMs",100);
        assertThat(awaitTerminal(token,start(token,create(token,slow))).get("failedRequests").asLong()).isGreaterThan(0);
        var redirect=awaitTerminal(token,start(token,create(token,config("/redirect",1,3))));
        assertThat(redirect.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(redirect.get("errorRate").asDouble()).isZero();
    }
    @Test void lowRateKeepsConfiguredDurationInsteadOfFinishingAfterFirstRequest() throws Exception {
        String token=token(); var input=config("/ok",2,20); input.put("requestsPerSecond",1);
        String id=start(token,create(token,input)); var completed=awaitTerminal(token,id);
        var samples=request("GET","/api/executions/"+id+"/metrics",token,null,200);
        assertThat(samples.get(samples.size()-1).get("elapsedSeconds").asDouble()).isGreaterThanOrEqualTo(1.9);
        assertThat(completed.get("totalRequests").asLong()).isBetween(1L,2L);
        assertThat(completed.get("throughput").asDouble()).isLessThanOrEqualTo(1.1);
    }
    @Test void accepts300VirtualUsersWithSharedRateCap() throws Exception {
        String token=token(); var input=config("/ok",2,300); input.put("requestsPerSecond",100);
        var completed=awaitTerminal(token,start(token,create(token,input)));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("totalRequests").asLong()).isBetween(1L,210L);
    }
    @Test void rejectsExcessExecutionsAndReleasesCapacity() throws Exception {
        String token=token(); String id=create(token,config("/slow",30,3));
        String a=start(token,id), b=start(token,id);
        request("POST","/api/test-configs/"+id+"/executions",token,Map.of("authorizedTarget",true),409);
        request("POST","/api/executions/"+a+"/stop",token,null,200); request("POST","/api/executions/"+b+"/stop",token,null,200);
        awaitTerminal(token,a); awaitTerminal(token,b);
    }
    @Test void manualDatasetsRotateAndArePrivate() throws Exception {
        bodies.clear(); String owner=token(), outsider=token(); var input=config("/ok",1,3);
        input.put("httpMethod","POST"); input.put("headers",Map.of("Content-Type","application/json"));
        String id=create(owner,input);
        var data=request("POST","/api/test-configs/"+id+"/mock-data",owner,Map.of("name","Samples","payloadsJson","[{\"id\":1},{\"id\":2}]"),201);
        request("POST","/api/test-configs/"+id+"/mock-data",owner,Map.of("name","Bad","payloadsJson","[]"),400);
        request("GET","/api/test-configs/"+id+"/mock-data",outsider,null,404);
        String execution=request("POST","/api/test-configs/"+id+"/executions",owner,Map.of("authorizedTarget",true,"mockDataSetId",data.get("id").asText()),202).get("id").asText();
        awaitTerminal(owner,execution); assertThat(bodies).contains("{\"id\":1}","{\"id\":2}");
    }
    @Test void interruptedExecutionIsRecoveredWithReport() throws Exception {
        String token=token(); String id=create(token,config("/ok",1,1));
        var jwtClaims=json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8));
        var owner=UUID.fromString(jwtClaims.get("sub").asText()); var config=configs.owned(UUID.fromString(id),owner);
        var pending=store.create(config,configs.request(config)); store.recoverInterrupted();
        var recovered=request("GET","/api/executions/"+pending.id,token,null,200);
        assertThat(recovered.get("status").asText()).isEqualTo("FAILED");
        assertThat(request("GET","/api/executions/"+pending.id+"/report",token,null,200).get("assessment").asText()).isEqualTo("ENGINE_INTERRUPTED");
    }
    @Test void aiEndpointsExplicitlyDeferred() throws Exception { request("POST","/api/ai/parse-prompt",token(),Map.of("prompt","test"),501); }
    @Test void websocketPublishesPrivateMetricsAndRejectsOtherOwners() throws Exception {
        String owner=token(), outsider=token(), id=create(owner,config("/ok",3,4)); String execution=start(owner,id);
        var client=new WebSocketStompClient(new StandardWebSocketClient()); client.setMessageConverter(new MappingJackson2MessageConverter());
        var headers=new StompHeaders(); headers.add("Authorization","Bearer "+owner);
        var received=new CompletableFuture<Map<?,?>>();
        var session=client.connectAsync("ws://localhost:"+port+"/ws",new org.springframework.web.socket.WebSocketHttpHeaders(),headers,new StompSessionHandlerAdapter() {}).get(5,TimeUnit.SECONDS);
        session.subscribe("/user/queue/executions/"+execution,new StompFrameHandler() {
            public Type getPayloadType(StompHeaders headers) { return Map.class; }
            public void handleFrame(StompHeaders headers,Object payload) { received.complete((Map<?,?>)payload); }
        });
        assertThat(received.get(5,TimeUnit.SECONDS).get("execution")).isNotNull(); session.disconnect();
        var denied=new CompletableFuture<Boolean>(); var outsiderHeaders=new StompHeaders(); outsiderHeaders.add("Authorization","Bearer "+outsider);
        var stranger=client.connectAsync("ws://localhost:"+port+"/ws",new org.springframework.web.socket.WebSocketHttpHeaders(),outsiderHeaders,new StompSessionHandlerAdapter() {
            @Override public Type getPayloadType(StompHeaders h) { return String.class; }
            @Override public void handleFrame(StompHeaders h,Object payload) { denied.complete(true); }
            @Override public void handleTransportError(StompSession s,Throwable e) { denied.complete(true); }
        }).get(5,TimeUnit.SECONDS);
        stranger.subscribe("/user/queue/executions/"+execution,new StompFrameHandler() {
            public Type getPayloadType(StompHeaders h) { return Map.class; }
            public void handleFrame(StompHeaders h,Object payload) { denied.complete(false); }
        });
        assertThat(denied.get(5,TimeUnit.SECONDS)).isTrue(); client.stop(); awaitTerminal(owner,execution);
    }
}
