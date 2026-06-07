package com.mytext.learningassistant.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import com.mytext.learningassistant.common.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OutboundUrlGuard {

    private final boolean allowPrivateNetwork;
    private final boolean resolveHosts;

    public OutboundUrlGuard(
        @Value("${app.security.outbound.allow-private-network:false}") boolean allowPrivateNetwork,
        @Value("${app.security.outbound.resolve-hosts:true}") boolean resolveHosts
    ) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolveHosts = resolveHosts;
    }

    public URI requirePublicHttpUrl(String value, boolean httpsOnly) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "URL 不能为空");
        }
        try {
            return requirePublicHttpUrl(URI.create(value.trim()), httpsOnly);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "URL 格式不正确");
        }
    }

    public URI requirePublicHttpUrl(URI uri, boolean httpsOnly) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly && !"https".equals(scheme)) {
            throw new BusinessException(400, "URL 必须使用 HTTPS");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(400, "URL 仅支持 HTTP/HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(400, "URL 缺少主机名");
        }
        validateHost(host);
        return uri;
    }

    private void validateHost(String host) {
        String normalizedHost = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);
        if (allowPrivateNetwork) {
            return;
        }
        if (isBlockedHostname(normalizedHost)) {
            throw new BusinessException(400, "不允许访问本机或内网地址");
        }
        validateIpLiteral(normalizedHost);
        if (!resolveHosts) {
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new BusinessException(400, "不允许访问本机或内网地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BusinessException(400, "URL 主机无法解析");
        }
    }

    private String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private boolean isBlockedHostname(String host) {
        return "localhost".equals(host)
            || host.endsWith(".localhost")
            || "metadata.google.internal".equals(host);
    }

    private void validateIpLiteral(String host) {
        if (host.matches("\\d+")) {
            throw new BusinessException(400, "不允许访问本机或内网地址");
        }
        if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.contains(":")) {
            try {
                if (isBlockedAddress(InetAddress.getByName(host))) {
                    throw new BusinessException(400, "不允许访问本机或内网地址");
                }
            } catch (UnknownHostException exception) {
                throw new BusinessException(400, "URL 主机无法解析");
            }
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || isBlockedIpv4(address)
            || isBlockedIpv6(address);
    }

    private boolean isBlockedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
            || first == 10
            || first == 127
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private boolean isBlockedIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        return first == 0
            || (first & 0xfe) == 0xfc
            || first == 0xfe;
    }
}
