package com.neuroplan.auth.session;

import java.time.Instant;

public record JwtSessionRecord(
        long id,
        long userId,
        String tokenId,
        String refreshTokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
