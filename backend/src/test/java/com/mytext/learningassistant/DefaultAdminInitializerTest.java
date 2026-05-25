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
class DefaultAdminInitializerTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private DefaultAdminInitializer initializer;

    @Test
    @Order(1)
    void startupCreatesDefaultAdmin() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordHasher.matches("12345678", admin.getPasswordHash())).isTrue();
    }

    @Test
    @Order(2)
    void rerunDoesNotOverwriteExistingAdminPasswordOrNicknameButKeepsAdminRole() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        String customHash = passwordHasher.hash("custom-password");
        admin.setPasswordHash(customHash);
        admin.setNickname("Custom Admin");
        admin.setRole(UserRole.USER);
        userRepository.save(admin);

        initializer.run();

        UserEntity updated = userRepository.findByUsername("admin").orElseThrow();
        assertThat(updated.getPasswordHash()).isEqualTo(customHash);
        assertThat(updated.getNickname()).isEqualTo("Custom Admin");
        assertThat(updated.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);

        updated.setPasswordHash(passwordHasher.hash("12345678"));
        updated.setNickname("admin");
        userRepository.save(updated);
    }
}
