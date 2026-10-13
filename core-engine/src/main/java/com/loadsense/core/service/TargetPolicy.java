package com.loadsense.core.service;

import com.loadsense.core.dto.ApiDtos.ConfigRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Component
public class TargetPolicy {
    private final Set<String> allowedHosts;
    private static final Set<String> RESTRICTED=Set.of("host","content-length","connection","upgrade","expect","transfer-encoding");
    public TargetPolicy(@Value("${loadsense.allowed-target-hosts}") List<String> hosts) {
        allowedHosts=new HashSet<>(); hosts.forEach(h -> allowedHosts.add(h.trim().toLowerCase(Locale.ROOT)));
    }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public URI validate(ConfigRequest c) {
        URI uri;
        try { uri=URI.create(c.targetUrl()); } catch(Exception e) { throw ApiException.invalid("URL không hợp lệ"); }
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getFragment()!=null || uri.getPort()>65535 || uri.getPort()==0) throw ApiException.invalid("Chỉ chấp nhận URL HTTP/HTTPS không có credentials hoặc fragment");
        if(!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw ApiException.invalid("Host API chưa nằm trong ALLOWED_TARGET_HOSTS");
        if(c.body()!=null && c.body().getBytes(StandardCharsets.UTF_8).length>65536) throw ApiException.invalid("Body tối đa 64 KiB");
        if(Set.of("GET","HEAD").contains(c.httpMethod()) && c.body()!=null && !c.body().isBlank()) throw ApiException.invalid("GET/HEAD không hỗ trợ request body");
        var headers=c.headers()==null ? Map.<String,String>of() : c.headers();
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
