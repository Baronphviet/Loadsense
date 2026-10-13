package com.loadsense.core.client;

import com.loadsense.core.dto.ApiDtos.ConfigRequest;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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
    private final ExecutorService io=Executors.newFixedThreadPool(16);
    private final TargetPolicy policy;
    private final CloseableHttpClient publicClient;
    private final HttpClient client=HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    public TargetHttpClient(TargetPolicy policy) {
        this.policy=policy;
        var resolver=new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try { return policy.ensurePublic(host); } catch(RuntimeException e) { throw new UnknownHostException("Target must resolve to public addresses"); }
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setMaxConnTotal(600).setMaxConnPerRoute(300).build();
        publicClient=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().build();
    }
    public int send(ConfigRequest c,String body,long remainingNanos) throws Exception {
        var uri=URI.create(c.targetUrl());
        if(!policy.isAdminHost(uri.getHost())) {
            policy.ensurePublic(uri.getHost());
            long timeoutMs=Math.max(1,Math.min(c.timeoutMs(),TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            var req=new HttpUriRequestBase(c.httpMethod(),uri);
            req.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(timeoutMs)).setResponseTimeout(Timeout.ofMilliseconds(timeoutMs)).setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs)).build());
            c.headers().forEach(req::addHeader);
            if(body!=null && !body.isEmpty()) req.setEntity(new StringEntity(body,java.nio.charset.StandardCharsets.UTF_8));
            return publicClient.execute(req,response -> response.getCode());
        }
        var request=HttpRequest.newBuilder(uri)
            .timeout(Duration.ofNanos(Math.max(1,Math.min(remainingNanos,c.timeoutMs()*1_000_000L))));
        c.headers().forEach(request::header);
        request.method(c.httpMethod(),body==null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @PreDestroy public void close() throws Exception { io.shutdownNow(); publicClient.close(); }
}
