package com.library.security;

import com.library.model.User;
import com.library.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-for-testing-only-min-32-chars";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", 3_600_000L);
    }

    @Test
    void generateToken_isValidAndExtractsClaims() {
        UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        User user = User.builder()
                .id(userId)
                .fullName("Jane Doe")
                .email("jane@example.com")
                .passwordHash("hash")
                .role(UserRole.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        String token = jwtService.generateToken(user);

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtService.extractEmail(token)).isEqualTo("jane@example.com");
    }

    @Test
    void isTokenValid_returnsFalseForMalformedToken() {
        assertThat(jwtService.isTokenValid("not-a-jwt")).isFalse();
    }

    @Test
    void isTokenValid_returnsFalseForTamperedToken() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Jane Doe")
                .email("jane@example.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        String token = jwtService.generateToken(user);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }
}
