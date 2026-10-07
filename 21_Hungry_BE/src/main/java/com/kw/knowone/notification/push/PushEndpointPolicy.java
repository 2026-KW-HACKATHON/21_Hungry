package com.kw.knowone.notification.push;

import com.kw.knowone.common.web.ApiException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PushEndpointPolicy {
    private final PushProperties properties;
    public PushEndpointPolicy(PushProperties properties) { this.properties=properties; }

    public URI validate(String raw) {
        try {
            URI uri=URI.create(raw);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) throw invalid();
            String host=IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
            boolean allowed=properties.allowedHostSuffixes()!=null && properties.allowedHostSuffixes().stream()
                    .map(v->v.trim().toLowerCase(Locale.ROOT)).filter(v->!v.isBlank())
                    .anyMatch(v->host.equals(v) || host.endsWith("."+v));
            if(!allowed) throw invalid();
            for(InetAddress address:InetAddress.getAllByName(host)) {
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) throw invalid();
            }
            return uri;
        } catch(ApiException exception) { throw exception; }
        catch(IllegalArgumentException|UnknownHostException exception) { throw invalid(); }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PUSH_ENDPOINT","허용되지 않는 Web Push endpoint입니다.");
    }
}
