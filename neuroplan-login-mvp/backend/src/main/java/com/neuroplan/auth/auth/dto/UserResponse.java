package com.neuroplan.auth.auth.dto;

import java.time.LocalDateTime;

import com.neuroplan.auth.user.UserRecord;

public record UserResponse(
        long id,
        String email,
        String nickname,
        String accountStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserResponse from(UserRecord user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.nickname(),
                user.accountStatus(),
                user.createdAt(),
                user.updatedAt()
        );
    }
}
