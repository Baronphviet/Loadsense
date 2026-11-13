package com.loadsense.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.service.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
public class AiClient {
    private final String url, token;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    public AiClient(@Value("${loadsense.ai-service-url:}") String url, @Value("${loadsense.ai-service-token:}") String token, ObjectMapper json) {
        this.url=url.replaceAll("/$", ""); this.token=token; this.json=json;
    }
    public boolean configured() { return !url.isBlank() && !token.isBlank(); }
    public boolean available() {
        if (!configured()) return false;
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/health")).timeout(Duration.ofSeconds(2)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200 && json.readTree(response.body()).path("aiEnabled").asBoolean(false);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }
    public JsonNode call(String path, Object body) {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_UNAVAILABLE","AI Service chưa được cấu hình");
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(120))
                .header("Content-Type","application/json").header("X-AI-Service-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==401 || response.statusCode()==403) {
                String msg="AI Service authentication failed";
                try {
                    var detail=json.readTree(response.body()).path("detail");
                    msg=detail.path("message").asText(msg);
                } catch (Exception ignored) {}
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_AUTH_ERROR",msg);
            }
            var result=json.readTree(response.body());
            if (response.statusCode()>=400) {
                var detail=result.path("detail");
                var status=HttpStatus.resolve(response.statusCode());
                throw new ApiException(status==null?HttpStatus.BAD_GATEWAY:status,detail.path("code").asText("AI_ERROR"),detail.path("message").asText("AI Service không thể xử lý yêu cầu"));
            }
            return result;
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_UNAVAILABLE","AI Service bị gián đoạn"); }
        catch (Exception e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_UNAVAILABLE","Không thể kết nối AI Service"); }
    }
}
