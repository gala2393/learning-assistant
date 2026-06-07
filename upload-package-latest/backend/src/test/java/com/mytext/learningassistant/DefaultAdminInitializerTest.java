package com.mytext.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.mytext.learningassistant.auth.PasswordHasher;
import com.mytext.learningassistant.user.DefaultAdminInitializer;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.user.UserStatus;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:default_admin_initializer_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
/**
 * 默认管理员初始化器测试。
 * <p>
 * 覆盖范围：应用启动时自动创建默认管理员账户、重复启动不会覆盖已修改的管理员信息。
 * 使用独立的 H2 内存数据库，通过 @Order 控制测试执行顺序。
 */
class DefaultAdminInitializerTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private DefaultAdminInitializer initializer;

    /**
     * 测试场景：应用首次启动时 DefaultAdminInitializer 自动创建默认管理员。
     * 预期结果：数据库中存在 username=admin 的账户，角色为 ADMIN，状态为 ACTIVE，
     *           密码哈希与配置的默认密码匹配。
     */
    @Test
    @Order(1)
    void startupCreatesDefaultAdmin() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordHasher.matches("test-admin-123456", admin.getPasswordHash())).isTrue();
    }

    /**
     * 测试场景：管理员账户已被手动修改后，再次执行初始化逻辑。
     * 预期结果：不会覆盖已修改的密码、昵称、角色和状态，保持用户自行修改的值不变。
     */
    @Test
    @Order(2)
    void rerunDoesNotOverwriteExistingAdminPasswordNicknameRoleOrStatus() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        String customHash = passwordHasher.hash("custom-password");
        admin.setPasswordHash(customHash);
        admin.setNickname("Custom Admin");
        admin.setRole(UserRole.USER);
        admin.setStatus(UserStatus.DISABLED);
        userRepository.save(admin);

        initializer.run();

        UserEntity updated = userRepository.findByUsername("admin").orElseThrow();
        assertThat(updated.getPasswordHash()).isEqualTo(customHash);
        assertThat(updated.getNickname()).isEqualTo("Custom Admin");
        assertThat(updated.getRole()).isEqualTo(UserRole.USER);
        assertThat(updated.getStatus()).isEqualTo(UserStatus.DISABLED);

        updated.setPasswordHash(passwordHasher.hash("test-admin-123456"));
        updated.setNickname("admin");
        updated.setRole(UserRole.ADMIN);
        updated.setStatus(UserStatus.ACTIVE);
        userRepository.save(updated);
    }
}
