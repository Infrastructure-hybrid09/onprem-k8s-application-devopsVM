package com.neuroplan.auth.user;

import java.time.LocalDateTime;

public record UserRecord(
        long id,
        String email,
        String passwordHash,
        String nickname,
        String accountStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
