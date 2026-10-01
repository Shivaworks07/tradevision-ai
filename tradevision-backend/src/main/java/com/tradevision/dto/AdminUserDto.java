package com.tradevision.dto;

import com.tradevision.model.User;

import java.time.LocalDateTime;

/**
 * Review finding (P1 #6 — "Your admin API leaks security fields"): confirmed real — the /users
 * endpoint was returning the raw User entity, including tokenVersion, refreshTokenHash,
 * refreshTokenExpiry, failedOtpAttempts, and lastFailedOtp. None of those are ever legitimate for
 * an admin dashboard to display, and refreshTokenHash in particular is an authentication
 * internal that should never leave the backend in any API response, hashed or not.
 */
public record AdminUserDto(
    String id,
    String firstName,
    String lastName,
    String email,
    String mobile,
    String role,
    boolean verified,
    LocalDateTime createdAt,
    LocalDateTime lastLogin
) {
    public static AdminUserDto from(User u) {
        return new AdminUserDto(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getMobile(),
            u.getRole(), u.isVerified(), u.getCreatedAt(), u.getLastLogin());
    }
}
