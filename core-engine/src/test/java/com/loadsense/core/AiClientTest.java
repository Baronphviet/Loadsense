package com.loadsense.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.client.AiClient;
import com.loadsense.core.service.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientTest {
    @Test void sendsAuthenticatedHttp11RequestWithoutH2cUpgrade() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var upgrade=new AtomicReference<String>(); var token=new AtomicReference<String>();
        server.createContext("/parse-prompt", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            token.set(exchange.getRequestHeaders().getFirst("X-AI-Service-Token"));
            var response="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            var client=new AiClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-token",new ObjectMapper());
            assertThat(client.call("/parse-prompt",Map.of("prompt","test")).path("ok").asBoolean()).isTrue();
            assertThat(upgrade.get()).isNull(); assertThat(token.get()).isEqualTo("test-token");
        } finally { server.stop(0); }
    }
    @Test void maps401And403To503AiAuthError() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/unauth", exchange -> {
            var response="{\"detail\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401,response.length);
            try(var out=exchange.getResponseBody()) { out.write(response); }
        });
        server.createContext("/forbidden", exchange -> {
            exchange.sendResponseHeaders(403,-1); exchange.close();
        });
        server.start();
        try {
            var client=new AiClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-token",new ObjectMapper());
            assertThatThrownBy(() -> client.call("/unauth",Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    var api=(ApiException)ex;
                    assertThat(api.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.code).isEqualTo("AI_AUTH_ERROR");
                });
            assertThatThrownBy(() -> client.call("/forbidden",Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    var api=(ApiException)ex;
                    assertThat(api.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.code).isEqualTo("AI_AUTH_ERROR");
                });
        } finally { server.stop(0); }
    }
}
