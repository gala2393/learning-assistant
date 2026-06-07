package com.mytext.learningassistant.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希工具组件 — 负责密码的加密、验证和升级。
 * <p>
 * 当前使用 BCrypt 算法（强度因子 12）作为主要的密码哈希方式，
 * 同时兼容旧系统遗留的 SHA-256 + Salt 哈希格式，实现无缝迁移。
 * <p>
 * 哈希格式说明：
 * <ul>
 *   <li>BCrypt 格式：以 "$2" 开头，如 "$2a$12$..."</li>
 *   <li>旧 SHA-256 格式：Base64(salt):Base64(digest)</li>
 * </ul>
 */
@Component
public class PasswordHasher {

    /** BCrypt 编码器，强度因子为 12（数值越大越安全，但计算越慢） */
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

    /**
     * 对明文密码进行 BCrypt 哈希加密。
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希后的密码字符串
     */
    public String hash(String rawPassword) {
        return bcrypt.encode(rawPassword);
    }

    /**
     * 验证明文密码是否与存储的哈希值匹配。
     * <p>
     * 自动识别存储值的格式：
     * <ul>
     *   <li>以 "$2" 开头 → 使用 BCrypt 验证</li>
     *   <li>其他格式 → 尝试使用旧的 SHA-256 + Salt 方式验证</li>
     * </ul>
     *
     * @param rawPassword  用户输入的明文密码
     * @param storedValue  数据库中存储的密码哈希值
     * @return true 表示密码匹配，false 表示不匹配
     */
    public boolean matches(String rawPassword, String storedValue) {
        // 空值安全检查
        if (rawPassword == null || storedValue == null || storedValue.isBlank()) {
            return false;
        }
        // 判断是否为 BCrypt 格式（以 "$2" 开头）
        if (storedValue.startsWith("$2")) {
            try {
                return bcrypt.matches(rawPassword, storedValue);
            } catch (RuntimeException exception) {
                return false;  // BCrypt 解析异常时返回不匹配
            }
        }
        // 兼容旧的 SHA-256 + Salt 格式
        return matchesLegacySha256(rawPassword, storedValue);
    }

    /**
     * 检查密码哈希是否需要重新加密（升级）。
     * <p>
     * 以下情况需要重新哈希：
     * <ul>
     *   <li>存储值为 null</li>
     *   <li>存储值不是 BCrypt 格式（即使用的是旧的 SHA-256 格式）</li>
     *   <li>BCrypt 强度因子低于当前配置（需要升级加密强度）</li>
     * </ul>
     *
     * @param storedValue 数据库中存储的密码哈希值
     * @return true 表示需要重新哈希，false 表示不需要
     */
    public boolean needsRehash(String storedValue) {
        return storedValue == null
            || !storedValue.startsWith("$2")        // 不是 BCrypt 格式
            || bcrypt.upgradeEncoding(storedValue);  // BCrypt 强度需要升级
    }

    /**
     * 使用旧的 SHA-256 + Salt 方式验证密码。
     * 仅为兼容旧数据而保留，新注册的用户全部使用 BCrypt。
     * <p>
     * 存储格式：Base64(salt):Base64(sha256(salt + password))
     *
     * @param rawPassword 用户输入的明文密码
     * @param storedValue 旧格式的哈希值
     * @return true 表示密码匹配，false 表示不匹配
     */
    private boolean matchesLegacySha256(String rawPassword, String storedValue) {
        // 按冒号分割为 salt 和 digest 两部分
        String[] parts = storedValue.split(":", 2);
        if (parts.length != 2) {
            return false;  // 格式不正确
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);            // 解码 salt
            byte[] expectedDigest = Base64.getDecoder().decode(parts[1]);  // 解码预期的摘要值
            byte[] actualDigest = digest(salt, rawPassword);               // 计算实际的摘要值
            return MessageDigest.isEqual(expectedDigest, actualDigest);    // 常量时间比较（防时序攻击）
        } catch (IllegalArgumentException exception) {
            return false;  // Base64 解码失败
        }
    }

    /**
     * 使用 SHA-256 算法计算密码摘要。
     * 先将 salt 更新到 MessageDigest 中，再计算密码的摘要值。
     *
     * @param salt        盐值（随机字节数组）
     * @param rawPassword 明文密码
     * @return SHA-256 摘要的字节数组
     * @throws IllegalStateException 如果 SHA-256 算法不可用时（正常情况不会发生）
     */
    private byte[] digest(byte[] salt, String rawPassword) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(salt);  // 先加入 salt
            return messageDigest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 Java 标准算法，正常情况不会出现此异常
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
