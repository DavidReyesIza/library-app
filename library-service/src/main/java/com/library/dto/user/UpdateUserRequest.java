package com.library.dto.user;

import com.library.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @Size(max = 150, message = "Full name must be at most 150 characters")
        String fullName,

        @Email(message = "Email must be valid")
        @Size(max = 255)
        String email,

        UserRole role
) {}
