package com.mytext.learningassistant.user;

import com.mytext.learningassistant.auth.PasswordHasher;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 默认管理员初始化器 — 应用启动时自动执行。
 * <p>
 * 实现 {@link ApplicationRunner} 接口的类会在 Spring Boot 启动完成后自动调用 run() 方法。
 * 确保数据库里始终有一个可用的管理员账号。
 * <p>
 * 逻辑：
 * <ol>
 *   <li>如果 admin 用户不存在 → 创建默认管理员（用户名 admin，密码 12345678）</li>
 *   <li>如果 admin 用户存在但角色不是 ADMIN 或状态不是 ACTIVE → 自动修正</li>
 *   <li>如果一切正常 → 什么都不做</li>
 * </ol>
 * <p>
 * ⚠️ 安全提醒：首次部署后请立即修改默认管理员密码！
 */
@Component
public class DefaultAdminInitializer implements ApplicationRunner {

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "12345678";
    private static final String DEFAULT_NICKNAME = "admin";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public DefaultAdminInitializer(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    /** ApplicationRunner 接口方法 — 应用启动后自动调用 */
    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    /**
     * 核心初始化逻辑：
     * 1. 查找 admin 用户
     * 2. 不存在则创建
     * 3. 存在则确保角色为 ADMIN、状态为 ACTIVE
     */
    public void run() {
        UserEntity admin = userRepository.findByUsername(DEFAULT_USERNAME).orElse(null);
        if (admin == null) {
            createDefaultAdmin();  // 首次部署：创建默认管理员
            return;
        }

        // admin 用户已存在，确保权限正确（可能被误修改过）
        boolean changed = false;
        if (admin.getRole() != UserRole.ADMIN) {
            admin.setRole(UserRole.ADMIN);
            changed = true;
        }
        if (admin.getStatus() != UserStatus.ACTIVE) {
            admin.setStatus(UserStatus.ACTIVE);
            changed = true;
        }
        if (changed) {
            userRepository.save(admin);  // 仅在有变化时才写数据库
        }
    }

    /** 创建默认管理员 — 密码通过 PasswordHasher 加密后存储 */
    private void createDefaultAdmin() {
        UserEntity admin = new UserEntity();
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPasswordHash(passwordHasher.hash(DEFAULT_PASSWORD));  // SHA-256 + 盐值
        admin.setNickname(DEFAULT_NICKNAME);
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        userRepository.save(admin);
    }
}
