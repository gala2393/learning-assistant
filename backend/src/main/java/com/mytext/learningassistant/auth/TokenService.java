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
 * Token（登录令牌）的生成与验证服务。
 * <p>
 * 用户登录成功后，后端生成一个 Token 返回给前端。前端将 Token 保存在 localStorage 中，
 * 后续所有需要登录的请求都在 Authorization 头中带上这个 Token。
 * 后端通过解析 Token 来识别"是谁在发请求"。
 * <p>
 * Token 格式：{base64Url(payload)}.{HMAC-SHA256签名}
 * <p>
 * Payload 内容：{userId}:{过期时间戳(毫秒)}
 * <p>
 * 为什么不使用 JWT？本项目自定义了一套更简洁的 Token 机制，避免了 JWT 库的额外依赖，
 * 同时实现了相同的安全性（签名防篡改 + 过期时间）。
 */
@Service
public class TokenService {

    /** 签名密钥（从配置文件读取，生产环境必须设置为强随机字符串） */
    private final String secret;
    /** Token 有效期（秒），默认 604800 = 7 天 */
    private final long ttlSeconds;

    public TokenService(
        @Value("${app.auth.secret:learning-assistant-secret}") String secret,
        @Value("${app.auth.token-ttl-seconds:604800}") long ttlSeconds
    ) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 为用户生成登录 Token。
     * <p>
     * 步骤：<ol>
     *   <li>计算过期时间 = 当前时间 + ttlSeconds</li>
     *   <li>构造 payload = "userId:过期时间戳"</li>
     *   <li>Base64Url 编码 payload</li>
     *   <li>用 HMAC-SHA256 对 payload 签名</li>
     *   <li>拼接：编码后的payload + "." + 签名</li>
     * </ol>
     *
     * @param user 登录成功的用户实体
     * @return Token 字符串（前端存入 localStorage）
     */
    public String createToken(UserEntity user) {
        // 计算过期时间：当前时间 + 有效期（秒）→ 毫秒时间戳
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).toEpochMilli();
        // payload = userId:过期时间戳
        String payload = user.getId() + ":" + expiresAt;
        // Token = Base64Url(payload).HMAC-SHA256签名
        return encode(payload) + "." + sign(payload);
    }

    /**
     * 解析 Token，提取用户 ID。
     * 同时验证 Token 格式是否正确、签名是否被篡改、是否已过期。
     * <p>
     * 任一检查失败都会抛出 BusinessException(401)，前端会收到 401 状态码并跳转到登录页。
     *
     * @param token Authorization 头中的 Bearer Token
     * @return 用户 ID
     * @throws BusinessException 如果 Token 无效、被篡改或已过期
     */
    public long parseUserId(String token) {
        // 1. 分割 payload 和签名（用 . 分隔）
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw new BusinessException(401, "token无效");  // 格式不正确
        }

        // 2. 解码 payload
        String payload = decode(parts[0]);

        // 3. 验证签名 — 如果被篡改过，重新计算的签名会不一致
        if (!sign(payload).equals(parts[1])) {
            throw new BusinessException(401, "token无效");  // 签名不匹配
        }

        // 4. 解析 payload 中的 userId 和过期时间
        String[] payloadParts = payload.split(":", 2);
        if (payloadParts.length != 2) {
            throw new BusinessException(401, "token无效");  // payload 格式不正确
        }
        long userId = Long.parseLong(payloadParts[0]);
        long expiresAt = Long.parseLong(payloadParts[1]);

        // 5. 检查是否过期
        if (Instant.now().toEpochMilli() > expiresAt) {
            throw new BusinessException(401, "token已过期");
        }

        return userId;
    }

    /**
     * 使用 HMAC-SHA256 算法对 payload 进行签名。
     * 签名保证 Token 不能被篡改——任何对 payload 的修改都会导致签名不匹配。
     */
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");                              // HMAC-SHA256 算法
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("token签名失败", exception);
        }
    }

    /** Base64Url 编码（URL 安全的 Base64，不含 +/ 字符，不使用 = 填充） */
    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Base64Url 解码 */
    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
