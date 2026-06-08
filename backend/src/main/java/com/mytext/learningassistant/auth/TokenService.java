package com.mytext.learningassistant.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.user.UserEntity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Token 服务 — 负责自定义 Token 的生成、签名和解析。
 * <p>
 * 本项目使用自实现的轻量级 Token 方案（类似简化版 JWT），格式为：
 * <pre>Base64Url(payload).Base64Url(HmacSHA256签名)</pre>
 * <p>
 * payload 内容格式：{@code userId:expiresAt:tokenVersion}
 * <ul>
 *   <li>userId — 用户的数据库 ID</li>
 *   <li>expiresAt — Token 过期时间的毫秒级时间戳</li>
 *   <li>tokenVersion — Token 版本号，用于实现"主动失效"（如修改密码或登出时递增）</li>
 * </ul>
 * <p>
 * 安全机制：
 * <ul>
 *   <li>使用 HmacSHA256 对 payload 进行签名，防止 Token 被篡改</li>
 *   <li>包含过期时间，防止 Token 被长期滥用</li>
 *   <li>包含 tokenVersion，服务端可以主动使所有旧 Token 失效</li>
 * </ul>
 */
@Service
public class TokenService {

    /** 默认密钥（仅用于开发/测试环境，生产环境必须通过配置覆盖） */
    private static final String DEFAULT_SECRET = "learning-assistant-secret";

    /** HmacSHA256 签名密钥 */
    private final String secret;

    /** Token 有效期，单位秒（默认 86400 秒 = 24 小时） */
    private final long ttlSeconds;

    /**
     * 构造方法，通过配置注入密钥和有效期。
     *
     * @param secret    签名密钥，配置项 app.auth.secret，默认为开发密钥
     * @param ttlSeconds Token 有效期（秒），配置项 app.auth.token-ttl-seconds，默认 86400（24小时）
     */
    public TokenService(
        @Value("${app.auth.secret:" + DEFAULT_SECRET + "}") String secret,
        @Value("${app.auth.token-ttl-seconds:86400}") long ttlSeconds
    ) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
        // 如果使用的是默认密钥，在日志中输出警告（方便在生产部署检查时发现问题）
        if (DEFAULT_SECRET.equals(secret)) {
            System.err.println("WARNING: app.auth.secret is using the development default. Set APP_AUTH_SECRET before production use.");
        }
    }

    /**
     * 为指定用户生成 Token。
     * <p>
     * Token payload 格式：{@code userId:expiresAt:tokenVersion}
     *
     * @param user 用户实体（包含 ID 和 tokenVersion）
     * @return 签名后的 Token 字符串
     */
    public String createToken(UserEntity user) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).toEpochMilli();  // 计算过期时间
        String payload = user.getId() + ":" + expiresAt + ":" + user.getTokenVersion();
        return encode(payload) + "." + sign(payload);  // Base64编码的payload + "." + 签名
    }

    /**
     * 从 Token 中解析用户 ID（便捷方法）。
     *
     * @param token Token 字符串
     * @return 用户 ID
     * @throws BusinessException Token 无效或已过期时
     */
    public long parseUserId(String token) {
        return parseClaims(token).userId();
    }

    /**
     * 解析 Token 并验证签名和有效期。
     * <p>
     * 验证步骤：
     * <ol>
     *   <li>拆分 Token 为 payload 和签名两部分</li>
     *   <li>验证签名是否正确（防篡改）</li>
     *   <li>解析 payload 中的 userId、expiresAt 和 tokenVersion</li>
     *   <li>检查 Token 是否已过期</li>
     * </ol>
     *
     * @param token Token 字符串
     * @return 解析出的 Token 声明（包含 userId 和 tokenVersion）
     * @throws BusinessException Token 格式错误、签名无效或已过期时
     */
    public TokenClaims parseClaims(String token) {
        // 拆分 Token 为 payload 和签名
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw invalidToken();
        }

        // 解码 payload 并验证签名
        String payload = decode(parts[0]);
        if (!sign(payload).equals(parts[1])) {
            // 重新计算签名后比较，任何 payload 篡改都会导致签名不匹配。
            throw invalidToken();  // 签名不匹配，Token 被篡改
        }

        // 解析 payload 的各个字段
        String[] payloadParts = payload.split(":", 3);
        if (payloadParts.length < 2) {
            throw invalidToken();
        }
        try {
            long userId = Long.parseLong(payloadParts[0]);              // 用户 ID
            long expiresAt = Long.parseLong(payloadParts[1]);           // 过期时间（毫秒时间戳）
            int tokenVersion = payloadParts.length == 3                 // Token 版本号（兼容旧 Token）
                ? Integer.parseInt(payloadParts[2]) : 0;
            // 检查 Token 是否已过期
            if (Instant.now().toEpochMilli() > expiresAt) {
                // 过期 Token 不再进入用户查询阶段，减少后续认证分支。
                throw new BusinessException(401, "token expired");
            }
            return new TokenClaims(userId, tokenVersion);
        } catch (NumberFormatException exception) {
            throw invalidToken();  // 数字格式解析失败
        }
    }

    /**
     * 创建 Token 无效的业务异常。
     *
     * @return BusinessException（HTTP 401）
     */
    private BusinessException invalidToken() {
        return new BusinessException(401, "token invalid");
    }

    /**
     * 使用 HmacSHA256 算法对 payload 进行签名。
     *
     * @param payload 需要签名的内容
     * @return Base64Url 编码的签名字符串
     * @throws IllegalStateException 签名过程失败时
     */
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("token signing failed", exception);
        }
    }

    /**
     * 将字符串编码为 Base64Url 格式（不含填充字符 "="）。
     *
     * @param value 原始字符串
     * @return Base64Url 编码后的字符串
     */
    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将 Base64Url 编码的字符串解码为原始字符串。
     *
     * @param value Base64Url 编码的字符串
     * @return 解码后的原始字符串
     * @throws BusinessException 解码失败时（Token 格式错误）
     */
    private String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();  // Base64 解码失败
        }
    }

    /**
     * Token 声明记录类 — 封装从 Token 中解析出的关键信息。
     *
     * @param userId       用户的数据库 ID
     * @param tokenVersion Token 版本号（用于实现主动失效机制）
     */
    public record TokenClaims(long userId, int tokenVersion) {
    }
}
