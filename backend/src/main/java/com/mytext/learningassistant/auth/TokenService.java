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

@Service
public class TokenService {

    private final String secret;
    private final long ttlSeconds;

    public TokenService(
        @Value("${app.auth.secret:learning-assistant-secret}") String secret,
        @Value("${app.auth.token-ttl-seconds:604800}") long ttlSeconds
    ) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    public String createToken(UserEntity user) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).toEpochMilli();
        String payload = user.getId() + ":" + expiresAt;
        return encode(payload) + "." + sign(payload);
    }

    public long parseUserId(String token) {
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw new BusinessException(401, "token无效");
        }
        String payload = decode(parts[0]);
        if (!sign(payload).equals(parts[1])) {
            throw new BusinessException(401, "token无效");
        }
        String[] payloadParts = payload.split(":", 2);
        if (payloadParts.length != 2) {
            throw new BusinessException(401, "token无效");
        }
        long userId = Long.parseLong(payloadParts[0]);
        long expiresAt = Long.parseLong(payloadParts[1]);
        if (Instant.now().toEpochMilli() > expiresAt) {
            throw new BusinessException(401, "token已过期");
        }
        return userId;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("token签名失败", exception);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
