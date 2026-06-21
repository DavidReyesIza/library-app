package com.library.dto.auth;

import com.library.model.UserRole;

import java.util.UUID;

public record AuthResponse(
        String token,
        String type,
        UUID userId,
        String email,
        UserRole role
) {
    public static AuthResponse of(String token, UUID userId, String email, UserRole role) {
        return new AuthResponse(token, "Bearer", userId, email, role);
    }
}
