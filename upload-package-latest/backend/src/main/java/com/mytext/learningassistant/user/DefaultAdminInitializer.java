package com.mytext.learningassistant.user;

import com.mytext.learningassistant.auth.PasswordHasher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 默认管理员初始化器 — 在应用启动时自动创建默认管理员账号。
 * <p>
 * 实现了 {@link ApplicationRunner} 接口，Spring Boot 启动完成后会自动调用 {@link #run(ApplicationArguments)} 方法。
 * <p>
 * 安全机制：
 * <ul>
 *   <li>必须通过配置 {@code app.admin.bootstrap-enabled=true} 显式启用</li>
 *   <li>如果数据库中已存在同名用户，则跳过创建（幂等操作）</li>
 *   <li>密码有最低强度要求：至少 12 个字符，且不能是常见弱密码</li>
 *   <li>用户名、密码、昵称均通过配置文件注入，不硬编码</li>
 * </ul>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * app:
 *   admin:
 *     bootstrap-enabled: true
 *     username: admin
 *     password: your-strong-password-here
 *     nickname: 系统管理员
 * </pre>
 */
@Component
public class DefaultAdminInitializer implements ApplicationRunner {

    /** 用户数据仓库，用于查询和保存用户 */
    private final UserRepository userRepository;

    /** 密码哈希工具，用于对管理员密码进行加密 */
    private final PasswordHasher passwordHasher;

    /** 是否启用默认管理员创建功能，配置项 app.admin.bootstrap-enabled，默认关闭 */
    private final boolean bootstrapEnabled;

    /** 管理员用户名，配置项 app.admin.username，默认为 "admin" */
    private final String username;

    /** 管理员密码，配置项 app.admin.password */
    private final String password;

    /** 管理员昵称，配置项 app.admin.nickname，默认与用户名相同 */
    private final String nickname;

    /**
     * 构造方法，通过配置注入各项参数。
     *
     * @param userRepository   用户数据仓库
     * @param passwordHasher   密码哈希工具
     * @param bootstrapEnabled 是否启用管理员初始化
     * @param username         管理员用户名
     * @param password         管理员密码
     * @param nickname         管理员昵称
     */
    public DefaultAdminInitializer(
        UserRepository userRepository,
        PasswordHasher passwordHasher,
        @Value("${app.admin.bootstrap-enabled:false}") boolean bootstrapEnabled,
        @Value("${app.admin.username:admin}") String username,
        @Value("${app.admin.password:}") String password,
        @Value("${app.admin.nickname:admin}") String nickname
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.bootstrapEnabled = bootstrapEnabled;
        this.username = normalize(username, "admin");             // 用户名为空时使用 "admin"
        this.password = password == null ? "" : password.trim();  // 密码不设默认值
        this.nickname = normalize(nickname, this.username);       // 昵称为空时使用用户名
    }

    /**
     * Spring Boot 启动完成后自动调用此方法。
     * 委托给 {@link #run()} 方法执行实际的初始化逻辑。
     *
     * @param args 应用启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    /**
     * 执行默认管理员的初始化逻辑。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查是否启用了管理员初始化功能</li>
     *   <li>验证密码强度（至少 12 个字符，不能是常见弱密码）</li>
     *   <li>检查数据库中是否已存在该用户名的用户</li>
     *   <li>如果不存在，创建并保存管理员用户</li>
     * </ol>
     */
    public void run() {
        // 未启用则直接返回
        if (!bootstrapEnabled) {
            return;
        }
        // 验证密码强度
        validateBootstrapPassword();
        // 如果管理员账号已存在，跳过创建（幂等操作）
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }

        // 创建管理员用户
        UserEntity admin = new UserEntity();
        admin.setUsername(username);
        admin.setPasswordHash(passwordHasher.hash(password));  // 密码哈希加密
        admin.setNickname(nickname);
        admin.setRole(UserRole.ADMIN);           // 角色为管理员
        admin.setStatus(UserStatus.ACTIVE);      // 状态为正常
        userRepository.save(admin);
    }

    /**
     * 验证管理员密码强度。
     * <p>
     * 要求：
     * <ul>
     *   <li>密码长度至少 12 个字符</li>
     *   <li>不能是常见的弱密码（如 "12345678"、"password"）</li>
     * </ul>
     *
     * @throws IllegalStateException 密码不符合强度要求时
     */
    private void validateBootstrapPassword() {
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (password.length() < 12 || lower.equals("12345678") || lower.equals("password")) {
            throw new IllegalStateException("app.admin.password must be at least 12 characters and not a known default password");
        }
    }

    /**
     * 规范化字符串值 — 去除首尾空格，如果为空则返回备选值。
     *
     * @param value    原始值
     * @param fallback 为空时使用的备选值
     * @return 规范化后的字符串
     */
    private String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
