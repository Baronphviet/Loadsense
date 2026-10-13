package com.loadsense.core.client;

import com.loadsense.core.dto.ApiDtos.ConfigRequest;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

@Component
public class TargetHttpClient {
    private final ExecutorService io=Executors.newFixedThreadPool(16);
    private final HttpClient client=HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    public int send(ConfigRequest c,String body,long remainingNanos) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(c.targetUrl()))
            .timeout(Duration.ofNanos(Math.max(1,Math.min(remainingNanos,c.timeoutMs()*1_000_000L))));
        c.headers().forEach(request::header);
        request.method(c.httpMethod(),body==null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @PreDestroy public void close() { io.shutdownNow(); }
}
