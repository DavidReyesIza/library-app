package com.library.config;

import com.library.model.User;
import com.library.model.UserRole;
import com.library.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates a default ADMIN user on first startup if none exists.
 * Credentials: admin@library.com / admin123
 *
 * This runs after Flyway migrations and only inserts if the email
 * is not already taken — safe to run on every restart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private static final String ADMIN_EMAIL    = "admin@library.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String ADMIN_NAME     = "Admin";

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            return;
        }

        User admin = User.builder()
                .fullName(ADMIN_NAME)
                .email(ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(UserRole.ADMIN)
                .build();

        userRepository.save(admin);
        log.info("Default admin user created: {}", ADMIN_EMAIL);
    }
}
