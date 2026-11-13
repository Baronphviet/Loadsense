package com.loadsense.core.client;

import com.loadsense.core.dto.ApiDtos.ConfigRequest;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import com.loadsense.core.service.TargetPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class TargetHttpClient {
    private static final int MAX_EXTRACTED_BODY_BYTES = 65536;
    private final ExecutorService io=Executors.newFixedThreadPool(16);
    private final TargetPolicy policy;
    private final CloseableHttpClient publicClient;
    private final HttpClient client=HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    public record HttpResult(int status, String body) {}
    public record ProbeResult(int status, String body, long latencyMs, Map<String, String> headers) {}
    private static final java.util.Set<String> SAFE_PROBE_HEADERS = java.util.Set.of(
        "content-type", "server", "location", "x-ratelimit-limit", "x-ratelimit-remaining",
        "x-ratelimit-reset", "retry-after", "cache-control");
    public TargetHttpClient(TargetPolicy policy) {
        this.policy=policy;
        var resolver=new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try { return policy.ensurePublic(host); } catch(RuntimeException e) { throw new UnknownHostException("Target must resolve to public addresses"); }
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setMaxConnTotal(600).setMaxConnPerRoute(300).build();
        publicClient=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
    }
    public int send(ConfigRequest c,String body,long remainingNanos) throws Exception {
        return sendWithResult(c.httpMethod(),c.targetUrl(),c.headers(),body,c.timeoutMs(),remainingNanos,false).status();
    }
    public HttpResult sendWithResult(String method,String targetUrl,Map<String,String> headers,String body,int timeoutMsSetting,long remainingNanos,boolean readBody) throws Exception {
        var uri=URI.create(targetUrl);
        Map<String,String> safeHeaders=headers==null ? Map.of() : headers;
        if(!policy.isAdminHost(uri.getHost())) {
            policy.ensurePublic(uri.getHost());
            long timeoutMs=Math.max(1,Math.min(timeoutMsSetting,TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            var req=new HttpUriRequestBase(method,uri);
            req.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(timeoutMs)).setResponseTimeout(Timeout.ofMilliseconds(timeoutMs)).setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs)).build());
            safeHeaders.forEach(req::addHeader);
            if(body!=null && !body.isEmpty()) req.setEntity(new StringEntity(body,StandardCharsets.UTF_8));
            return publicClient.execute(req,response -> {
                String respBody=null;
                if(readBody && response.getEntity()!=null) {
                    try(InputStream in=response.getEntity().getContent()) {
                        respBody=new String(in.readNBytes(MAX_EXTRACTED_BODY_BYTES),StandardCharsets.UTF_8);
                    }
                }
                return new HttpResult(response.getCode(),respBody);
            });
        }
        var request=HttpRequest.newBuilder(uri)
            .timeout(Duration.ofNanos(Math.max(1,Math.min(remainingNanos,timeoutMsSetting*1_000_000L))));
        safeHeaders.forEach(request::header);
        request.method(method,body==null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(readBody) {
            var res=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=res.body()) {
                String respBody=in==null ? null : new String(in.readNBytes(MAX_EXTRACTED_BODY_BYTES),StandardCharsets.UTF_8);
                return new HttpResult(res.statusCode(),respBody);
            }
        }
        return new HttpResult(client.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode(),null);
    }
    public ProbeResult probeWithHeaders(String method, String targetUrl, Map<String, String> headers, String body, int timeoutMsSetting) throws Exception {
        long started = System.nanoTime();
        var uri = URI.create(targetUrl);
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        if (!policy.isAdminHost(uri.getHost())) {
            policy.ensurePublic(uri.getHost());
            var req = new HttpUriRequestBase(method, uri);
            req.setConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMsSetting))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMsSetting))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMsSetting))
                .build());
            safeHeaders.forEach(req::addHeader);
            if (body != null && !body.isEmpty()) req.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
            return publicClient.execute(req, response -> {
                String respBody = null;
                if (response.getEntity() != null) {
                    try (InputStream in = response.getEntity().getContent()) {
                        respBody = new String(in.readNBytes(MAX_EXTRACTED_BODY_BYTES), StandardCharsets.UTF_8);
                    }
                }
                Map<String, String> collected = new java.util.LinkedHashMap<>();
                for (var h : response.getHeaders()) {
                    String name = h.getName() == null ? "" : h.getName().toLowerCase(java.util.Locale.ROOT);
                    if (SAFE_PROBE_HEADERS.contains(name) && h.getValue() != null) collected.putIfAbsent(name, h.getValue());
                }
                long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                return new ProbeResult(response.getCode(), respBody, latencyMs, collected);
            });
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMsSetting));
        safeHeaders.forEach(request::header);
        request.method(method, body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var res = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            String respBody = in == null ? null : new String(in.readNBytes(MAX_EXTRACTED_BODY_BYTES), StandardCharsets.UTF_8);
            Map<String, String> collected = new java.util.LinkedHashMap<>();
            res.headers().map().forEach((k, v) -> {
                String name = k == null ? "" : k.toLowerCase(java.util.Locale.ROOT);
                if (SAFE_PROBE_HEADERS.contains(name) && v != null && !v.isEmpty()) collected.putIfAbsent(name, v.get(0));
            });
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            return new ProbeResult(res.statusCode(), respBody, latencyMs, collected);
        }
    }
    @PreDestroy public void close() throws Exception { io.shutdownNow(); publicClient.close(); }
}
