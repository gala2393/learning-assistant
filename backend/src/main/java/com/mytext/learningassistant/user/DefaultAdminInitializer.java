package com.mytext.learningassistant.user;

import com.mytext.learningassistant.auth.PasswordHasher;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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

    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    public void run() {
        UserEntity admin = userRepository.findByUsername(DEFAULT_USERNAME).orElse(null);
        if (admin == null) {
            createDefaultAdmin();
            return;
        }

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
            userRepository.save(admin);
        }
    }

    private void createDefaultAdmin() {
        UserEntity admin = new UserEntity();
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPasswordHash(passwordHasher.hash(DEFAULT_PASSWORD));
        admin.setNickname(DEFAULT_NICKNAME);
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        userRepository.save(admin);
    }
}
