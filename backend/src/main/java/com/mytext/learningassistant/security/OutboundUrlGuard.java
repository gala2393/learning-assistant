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

/**
 * 出站 URL 安全校验服务 —— 防止 SSRF（Server-Side Request Forgery，服务端请求伪造）攻击。
 *
 * <h3>什么是 SSRF 攻击？</h3>
 * <p>
 * SSRF 是指攻击者通过构造恶意 URL，诱使服务器向内网或本机发送请求，从而：
 * </p>
 * <ul>
 *   <li>访问内网服务（如 Redis、数据库管理界面、云平台元数据接口等）</li>
 *   <li>扫描内网拓扑结构</li>
 *   <li>读取本机敏感文件（如 file:///etc/passwd）</li>
 * </ul>
 * <p>
 * 例如，如果用户提交的"学习资料 URL"指向 {@code http://169.254.169.254/latest/meta-data/}
 * （AWS 元数据接口），服务器可能会泄露云服务器的密钥和配置信息。
 * </p>
 *
 * <h3>防护机制</h3>
 * <ol>
 *   <li><b>协议白名单</b> —— 只允许 HTTP/HTTPS 协议，拒绝 file://、ftp://、gopher:// 等危险协议</li>
 *   <li><b>主机名黑名单</b> —— 拒绝 localhost、*.localhost、metadata.google.internal 等敏感主机名</li>
 *   <li><b>IP 地址黑名单</b> —— 拒绝所有内网 IP 地址段（详见 {@link #isBlockedIpv4}）</li>
 *   <li><b>DNS 解析验证</b> —— 对域名进行 DNS 解析，检查解析结果是否为内网 IP（防止 DNS 重绑定攻击）</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code app.security.outbound.allow-private-network} —— 是否允许访问内网地址（默认 false）。
 *       开发环境可能需要设为 true 以便访问本地服务。</li>
 *   <li>{@code app.security.outbound.resolve-hosts} —— 是否对域名做 DNS 解析验证（默认 true）。
 *       设为 false 可跳过 DNS 检查，但会降低安全性。</li>
 * </ul>
 */
@Service
public class OutboundUrlGuard {

    /** 是否允许访问内网地址（开发环境可设为 true）。 */
    private final boolean allowPrivateNetwork;

    /** 是否对域名进行 DNS 解析并验证解析结果。 */
    private final boolean resolveHosts;

    /**
     * 构造函数，从配置文件读取安全策略参数。
     *
     * @param allowPrivateNetwork 是否允许访问内网（默认 false，即禁止）
     * @param resolveHosts        是否解析域名并验证 IP（默认 true，即启用）
     */
    public OutboundUrlGuard(
        @Value("${app.security.outbound.allow-private-network:false}") boolean allowPrivateNetwork,
        @Value("${app.security.outbound.resolve-hosts:true}") boolean resolveHosts
    ) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolveHosts = resolveHosts;
    }

    /**
     * 校验字符串形式的 URL，确保它是安全的公网 HTTP(S) 地址。
     * <p>校验通过返回解析后的 {@link URI} 对象，不通过抛出异常。</p>
     *
     * @param value    URL 字符串
     * @param httpsOnly 是否只允许 HTTPS（为 true 时拒绝 HTTP）
     * @return 校验通过的 URI 对象
     * @throws BusinessException URL 为空、格式不正确、或指向内网地址时抛出
     */
    public URI requirePublicHttpUrl(String value, boolean httpsOnly) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "URL 不能为空");
        }
        try {
            // 先去除首尾空白，再解析为 URI 对象
            return requirePublicHttpUrl(URI.create(value.trim()), httpsOnly);
        } catch (IllegalArgumentException exception) {
            // URI.create() 在格式非法时抛出 IllegalArgumentException
            throw new BusinessException(400, "URL 格式不正确");
        }
    }

    /**
     * 校验 URI 对象，依次检查协议、主机名和 IP 地址。
     *
     * @param uri       要校验的 URI
     * @param httpsOnly 是否只允许 HTTPS
     * @return 校验通过的 URI（原样返回）
     * @throws BusinessException 校验不通过时抛出
     */
    public URI requirePublicHttpUrl(URI uri, boolean httpsOnly) {
        // 第一步：校验协议（scheme）
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        // 如果要求 HTTPS only，但协议不是 https，则拒绝
        if (httpsOnly && !"https".equals(scheme)) {
            throw new BusinessException(400, "URL 必须使用 HTTPS");
        }
        // 只允许 http 和 https，拒绝 file://、ftp://、gopher:// 等危险协议
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(400, "URL 仅支持 HTTP/HTTPS");
        }

        // 第二步：校验主机名是否存在
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(400, "URL 缺少主机名");
        }

        // 第三步：深入校验主机名（黑名单 + IP 检查 + DNS 解析验证）
        validateHost(host);
        return uri;
    }

    /**
     * 校验主机名的安全性。
     * <p>
     * 校验顺序：
     * 1. 去除 IPv6 地址的方括号
     * 2. 检查是否在主机名黑名单中（localhost 等）
     * 3. 如果主机名本身就是 IP 地址，检查是否为内网 IP
     * 4. 如果启用了 DNS 解析验证，解析域名并检查所有解析结果
     * </p>
     *
     * @param host 主机名
     * @throws BusinessException 主机名指向内网地址时抛出
     */
    private void validateHost(String host) {
        String normalizedHost = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);

        // 如果配置允许内网访问，跳过所有检查（开发模式）
        if (allowPrivateNetwork) {
            return;
        }

        // 检查主机名黑名单（localhost、*.localhost、云平台元数据地址）
        if (isBlockedHostname(normalizedHost)) {
            throw new BusinessException(400, "不允许访问本机或内网地址");
        }

        // 如果主机名是 IP 地址字面量（如 "192.168.1.1" 或 "::1"），直接检查 IP
        validateIpLiteral(normalizedHost);

        // 如果不需要 DNS 解析验证，到此结束
        if (!resolveHosts) {
            return;
        }

        // 第四步：对域名进行 DNS 解析，检查解析结果是否指向内网
        // 这是为了防止"DNS 重绑定"攻击：攻击者先让域名解析到公网 IP 通过校验，
        // 然后快速将 DNS 改为内网 IP，服务器实际请求时就会访问内网
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

    /**
     * 去除 IPv6 地址的方括号。
     * <p>
     * URI 中的 IPv6 地址格式为 "[::1]"，但 InetAddress 解析时不需要方括号。
     * </p>
     *
     * @param host 可能带方括号的主机名
     * @return 去除方括号后的主机名
     */
    private String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * 检查主机名是否在黑名单中。
     * <p>
     * 黑名单包含：
     * <ul>
     *   <li>{@code localhost} —— 本机回环地址的主机名</li>
     *   <li>{@code *.localhost} —— localhost 的任意子域名（某些浏览器和系统会解析为 127.0.0.1）</li>
     *   <li>{@code metadata.google.internal} —— Google Cloud 的元数据服务地址</li>
     * </ul>
     * </p>
     *
     * @param host 标准化后的主机名
     * @return 如果在黑名单中返回 true
     */
    private boolean isBlockedHostname(String host) {
        return "localhost".equals(host)
            || host.endsWith(".localhost")
            || "metadata.google.internal".equals(host);
    }

    /**
     * 校验 IP 地址字面量（即主机名本身就是 IP 而非域名）。
     * <p>
     * 注意：纯数字（如 "12345"）也会被某些系统当作 IP 地址解析（解释为十六进制），
     * 因此这里先用正则排除纯数字的情况。
     * </p>
     *
     * @param host 主机名
     * @throws BusinessException IP 地址指向内网时抛出
     */
    private void validateIpLiteral(String host) {
        // 纯数字（如 "2130706433"）在某些系统中会被解析为 IP，这里直接拒绝
        if (host.matches("\\d+")) {
            throw new BusinessException(400, "不允许访问本机或内网地址");
        }
        // 匹配 IPv4 格式（如 "192.168.1.1"）或包含冒号的 IPv6 地址（如 "::1"）
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

    /**
     * 综合判断一个 IP 地址是否应该被阻止。
     * <p>
     * 使用 InetAddress 自带的分类方法 + 自定义的 IPv4/IPv6 黑名单段，
     * 多层防护确保不遗漏任何内网地址。
     * </p>
     *
     * @param address 解析后的 IP 地址对象
     * @return 如果应该被阻止返回 true
     */
    private boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()       // 0.0.0.0 或 :: （"任意地址"，通常表示本机所有网卡）
            || address.isLoopbackAddress()        // 127.0.0.1 或 ::1 （回环地址，即本机）
            || address.isLinkLocalAddress()       // 169.254.x.x 或 fe80:: （链路本地地址）
            || address.isSiteLocalAddress()       // 10.x.x.x / 172.16-31.x.x / 192.168.x.x （私有地址）
            || address.isMulticastAddress()       // 224.x.x.x 及以上（组播地址，不应作为请求目标）
            || isBlockedIpv4(address)             // 自定义 IPv4 黑名单段
            || isBlockedIpv6(address);            // 自定义 IPv6 黑名单段
    }

    /**
     * IPv4 地址黑名单 —— 覆盖所有常见的内网/保留 IP 段。
     * <p>
     * 完整的阻止列表（通过第一个和第二个字节判断）：
     * </p>
     * <table border="1">
     *   <tr><th>IP 段</th><th>CIDR</th><th>用途</th></tr>
     *   <tr><td>0.x.x.x</td><td>0.0.0.0/8</td><td>"当前网络"，保留地址</td></tr>
     *   <tr><td>10.x.x.x</td><td>10.0.0.0/8</td><td>A 类私有地址（最常见的内网段）</td></tr>
     *   <tr><td>127.x.x.x</td><td>127.0.0.0/8</td><td>回环地址（整个 127 段都是本机）</td></tr>
     *   <tr><td>100.64-127.x.x</td><td>100.64.0.0/10</td><td>运营商级 NAT（CGN）地址</td></tr>
     *   <tr><td>169.254.x.x</td><td>169.254.0.0/16</td><td>链路本地地址（DHCP 失败时自动分配）</td></tr>
     *   <tr><td>172.16-31.x.x</td><td>172.16.0.0/12</td><td>B 类私有地址（Docker 默认网段等）</td></tr>
     *   <tr><td>192.168.x.x</td><td>192.168.0.0/16</td><td>C 类私有地址（家用路由器最常见）</td></tr>
     *   <tr><td>198.18-19.x.x</td><td>198.18.0.0/15</td><td>基准测试用地址</td></tr>
     *   <tr><td>224.x.x.x 及以上</td><td>224.0.0.0/4</td><td>组播/D 类/E 类保留地址</td></tr>
     * </table>
     *
     * <p>
     * 为什么要单独列出这些？因为 {@link InetAddress#isSiteLocalAddress()} 只覆盖
     * 10/172.16/192.168 三段，而 0.x、127.x、100.64.x、169.254.x、198.18.x、224+ 等段需要手动判断。
     * </p>
     *
     * @param address IP 地址对象
     * @return 如果在 IPv4 黑名单中返回 true
     */
    private boolean isBlockedIpv4(InetAddress address) {
        // 只对 IPv4 地址进行检查，IPv6 直接跳过
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;   // 第一个字节（& 0xff 将有符号 byte 转为无符号 int）
        int second = bytes[1] & 0xff;  // 第二个字节
        return first == 0                                            // 0.x.x.x —— 保留地址
            || first == 10                                           // 10.x.x.x —— A 类私有
            || first == 127                                          // 127.x.x.x —— 回环地址
            || (first == 100 && second >= 64 && second <= 127)       // 100.64.0.0/10 —— CGN
            || (first == 169 && second == 254)                       // 169.254.x.x —— 链路本地
            || (first == 172 && second >= 16 && second <= 31)        // 172.16.0.0/12 —— B 类私有
            || (first == 192 && second == 168)                       // 192.168.x.x —— C 类私有
            || (first == 198 && (second == 18 || second == 19))      // 198.18.0.0/15 —— 基准测试
            || first >= 224;                                         // 224+ —— 组播/保留地址
    }

    /**
     * IPv6 地址黑名单 —— 阻止内网和保留的 IPv6 地址段。
     * <p>
     * IPv6 地址的第一个字节（前 8 位）可以判断地址类型：
     * </p>
     * <ul>
     *   <li>0x00 —— 未指定地址 (::) 和 IPv4 映射地址 (::ffff:0:0/96)</li>
     *   <li>0xfc 或 0xfd —— 唯一本地地址（ULA），类似 IPv4 的私有地址</li>
     *   <li>0xfe —— 链路本地地址（fe80::/10）和站点本地地址（已废弃）</li>
     * </ul>
     *
     * @param address IP 地址对象
     * @return 如果在 IPv6 黑名单中返回 true
     */
    private boolean isBlockedIpv6(InetAddress address) {
        // 只对 IPv6 地址进行检查
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff; // 第一个字节
        return first == 0              // 0x00 —— 未指定/IPv4 映射地址
            || (first & 0xfe) == 0xfc  // 0xfc~0xfd —— 唯一本地地址（ULA），fe 的二进制是 11111110，与操作后匹配 fc 和 fd
            || first == 0xfe;          // 0xfe —— 链路本地/站点本地地址
    }
}
