package com.loadsense.core.service;

import com.loadsense.core.dto.ApiDtos.ConfigRequest;
import com.loadsense.core.dto.ApiDtos.ScenarioStep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.InetAddress;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import com.loadsense.core.repository.ApprovedTargetHostRepository;
import com.loadsense.core.entity.ApprovedTargetHost;

@Component
public class TargetPolicy {
    private final Set<String> allowedHosts;
    private final ApprovedTargetHostRepository approved;
    private static final Set<String> RESTRICTED=Set.of("host","content-length","connection","upgrade","expect","transfer-encoding");
    private static final Pattern TEMPLATE_VAR=Pattern.compile("\\{\\{[a-zA-Z_][a-zA-Z0-9_]*\\}\\}");
    public TargetPolicy(@Value("${loadsense.allowed-target-hosts}") List<String> hosts, ApprovedTargetHostRepository approved) {
        this.approved=approved;
        allowedHosts=new HashSet<>(); hosts.forEach(h -> allowedHosts.add(h.trim().toLowerCase(Locale.ROOT)));
    }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public boolean isAdminHost(String host) { return allowedHosts.contains(host.toLowerCase(Locale.ROOT)); }
    public Set<String> allowedHosts(UUID owner) {
        var result=new HashSet<>(allowedHosts); approved.findByUserId(owner).forEach(h -> result.add(h.host)); return result;
    }
    public void approve(UUID owner,String input) {
        var host=input.trim().toLowerCase(Locale.ROOT);
        try {
            var uri=URI.create("http://"+host);
            if(uri.getHost()==null || !uri.getHost().equals(host) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getRawPath()!=null && !uri.getRawPath().isEmpty() || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
                throw ApiException.invalid("Chỉ nhập tên miền hoặc địa chỉ IP, không kèm cổng hoặc đường dẫn");
        } catch(ApiException e) { throw e; } catch(Exception e) { throw ApiException.invalid("Host không hợp lệ"); }
        ensurePublic(host);
        if(!allowedHosts.contains(host) && !approved.existsByUserIdAndHost(owner,host)) {
            var item=new ApprovedTargetHost(); item.userId=owner; item.host=host; approved.save(item);
        }
    }
    public InetAddress[] ensurePublic(String host) {
        try {
            var addresses=InetAddress.getAllByName(host);
            for(var address:addresses) {
                byte[] bytes=address.getAddress();
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IllegalArgumentException();
                if(bytes.length==4) {
                    int a=bytes[0]&255,b=bytes[1]&255;
                    if(a==0 || a==10 || a==127 || a>=224 || a==169 && b==254 || a==172 && b>=16 && b<=31 || a==192 && b==168 || a==100 && b>=64 && b<=127 || a==198 && (b==18 || b==19)) throw new IllegalArgumentException();
                } else if(bytes.length==16 && (bytes[0]&0xe0)!=0x20) throw new IllegalArgumentException();
            }
            return addresses;
        } catch(Exception e) { throw ApiException.invalid("Host phải phân giải tới địa chỉ Internet công khai; host nội bộ cần được quản trị viên cấu hình"); }
    }
    public URI validate(ConfigRequest c,UUID owner) {
        URI primary=validateEndpoint(c.targetUrl(),c.httpMethod(),c.headers(),c.body(),owner,false);
        var steps=c.resolvedSteps();
        if(!steps.isEmpty()) {
            if(steps.size()>3) throw ApiException.invalid("Scenario Chaining tối đa 3 bước");
            for(ScenarioStep step:steps) {
                validateEndpoint(step.targetUrl(),step.httpMethod(),step.headers(),step.body(),owner,true);
            }
        }
        return primary;
    }
    public URI validateEndpointForProbe(String rawUrl, String httpMethod, Map<String,String> headersMap, String body, UUID owner, boolean autoApprove) {
        if (autoApprove && rawUrl != null) {
            URI parsed;
            try { parsed = URI.create(rawUrl.trim()); } catch (Exception e) { throw ApiException.invalid("URL không hợp lệ"); }
            if (parsed.getHost() != null) {
                String host = parsed.getHost().toLowerCase(Locale.ROOT);
                if (!allowedHosts.contains(host)) approve(owner, host);
            }
        }
        return validateEndpoint(rawUrl, httpMethod, headersMap, body, owner, false);
    }
    private URI validateEndpoint(String rawUrl,String httpMethod,Map<String,String> headersMap,String body,UUID owner,boolean allowTemplates) {
        String candidate=rawUrl;
        if(allowTemplates && rawUrl!=null && (rawUrl.contains("{") || rawUrl.contains("}"))) {
            int schemeIdx=rawUrl.indexOf("://");
            if(schemeIdx<=0) throw ApiException.invalid("URL không hợp lệ");
            int pathIdx=rawUrl.indexOf('/',schemeIdx+3);
            String authority=pathIdx>=0 ? rawUrl.substring(0,pathIdx) : rawUrl;
            if(authority.contains("{") || authority.contains("}")) throw ApiException.invalid("Không hỗ trợ biến {{...}} trong giao thức hoặc tên miền URL");
            String rest=pathIdx>=0 ? rawUrl.substring(pathIdx) : "";
            String replaced=TEMPLATE_VAR.matcher(rest).replaceAll("var");
            if(replaced.contains("{") || replaced.contains("}")) throw ApiException.invalid("Cú pháp biến {{ten_bien}} trong URL không hợp lệ");
            candidate=authority+replaced;
        }
        URI uri;
        try { uri=URI.create(candidate); } catch(Exception e) { throw ApiException.invalid("URL không hợp lệ"); }
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getFragment()!=null || uri.getPort()>65535 || uri.getPort()==0) throw ApiException.invalid("Chỉ chấp nhận URL HTTP/HTTPS không có credentials hoặc fragment");
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        if(!allowedHosts.contains(host)) {
            if(!approved.existsByUserIdAndHost(owner,host)) throw ApiException.invalid("Host chưa được bạn xác nhận quyền kiểm thử");
            ensurePublic(host);
        }
        if(body!=null && body.getBytes(StandardCharsets.UTF_8).length>65536) throw ApiException.invalid("Body tối đa 64 KiB");
        if(Set.of("GET","HEAD").contains(httpMethod) && body!=null && !body.isBlank()) throw ApiException.invalid("GET/HEAD không hỗ trợ request body");
        var headers=headersMap==null ? Map.<String,String>of() : headersMap;
        if(headers.size()>32) throw ApiException.invalid("Tối đa 32 header");
        int bytes=0;
        for(var entry:headers.entrySet()) {
            String key=entry.getKey(), value=entry.getValue();
            if(key==null || !key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || RESTRICTED.contains(key.toLowerCase(Locale.ROOT))
                || value==null || value.chars().anyMatch(ch -> ch<32 || ch==127 || ch>255)) throw ApiException.invalid("Header không hợp lệ hoặc thuộc nhóm bị hạn chế");
            bytes+=key.length()+value.getBytes(StandardCharsets.UTF_8).length;
        }
        if(bytes>16384) throw ApiException.invalid("Headers tối đa 16 KiB");
        return uri;
    }
}
