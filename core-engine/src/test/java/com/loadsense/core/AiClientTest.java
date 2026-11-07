package com.loadsense.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.client.AiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

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
}
