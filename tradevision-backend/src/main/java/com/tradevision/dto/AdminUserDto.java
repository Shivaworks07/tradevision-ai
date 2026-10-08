package com.tradevision.dto;

import com.tradevision.model.User;

import java.time.LocalDateTime;

/**
 * Safe projection of a User for the admin users list: deliberately excludes authentication
 * internals (tokenVersion, refreshTokenHash, refreshTokenExpiry, failedOtpAttempts,
 * lastFailedOtp), none of which should ever leave the backend in an API response.
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
