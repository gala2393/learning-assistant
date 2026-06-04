package com.mytext.learningassistant.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * 密码加密工具 — 使用 SHA-256 + 随机盐值对密码进行哈希。
 * 密码永远不会以明文存储到数据库，只存储哈希结果。
 * <p>
 * 存储格式：{base64(16字节随机盐值)}:{base64(SHA256(盐值+原始密码))}
 * <p>
 * 为什么需要盐值（Salt）？</b>
 * 如果不加盐，两个密码相同的用户会产生相同的哈希值，攻击者可以用
 * "彩虹表"（预先计算好的常见密码→哈希值对照表）反向查找。
 * 加上随机盐值后，即使密码相同，哈希结果也完全不同。
 */
@Component
public class PasswordHasher {

    /** 安全的随机数生成器，用于生成盐值 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 对原始密码进行哈希。
     * 1. 生成 16 字节随机盐值
     * 2. 计算 SHA-256(盐值 + 密码)
     * 3. 返回 {base64盐值}:{base64哈希值}
     *
     * @param rawPassword 用户输入的原始密码
     * @return 加密后的密码字符串（存入数据库 password_hash 字段）
     */
    public String hash(String rawPassword) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);                                          // 生成随机盐值
        byte[] digest = digest(salt, rawPassword);                             // 计算哈希
        return Base64.getEncoder().encodeToString(salt) + ":" +                // 盐值:哈希值
               Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 验证密码是否正确。
     * 1. 从存储值中分离盐值和期望的哈希值
     * 2. 用同样的盐值重新计算哈希
     * 3. 比较重新计算的哈希和存储的哈希是否一致
     * <p>
     * 使用 {@code MessageDigest.isEqual()} 做时间安全的比较，防止时序攻击。
     *
     * @param rawPassword  用户输入的原始密码
     * @param storedValue  数据库中存储的密码哈希值（格式：base64盐值:base64哈希值）
     * @return true=密码匹配，false=不匹配
     */
    public boolean matches(String rawPassword, String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return false;  // 没有存储的密码，不可能匹配
        }
        String[] parts = storedValue.split(":", 2);
        if (parts.length != 2) {
            return false;  // 存储格式不正确
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);            // 解析盐值
            byte[] expectedDigest = Base64.getDecoder().decode(parts[1]);  // 期望的哈希值
            byte[] actualDigest = digest(salt, rawPassword);               // 重新计算
            // 时间安全的比较（不会因字符匹配个数不同而花费不同时间，防止暴力破解）
            return MessageDigest.isEqual(expectedDigest, actualDigest);
        } catch (IllegalArgumentException exception) {
            return false;  // Base64 解码失败
        }
    }

    /**
     * 计算 SHA-256(盐值 + 密码)。
     * 先 update 盐值，再 digest 密码，等效于对"盐值+密码"的整体做哈希。
     */
    private byte[] digest(byte[] salt, String rawPassword) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(salt);                                        // 先放入盐值
            return messageDigest.digest(rawPassword.getBytes(StandardCharsets.UTF_8)); // 再放入密码，计算哈希
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
