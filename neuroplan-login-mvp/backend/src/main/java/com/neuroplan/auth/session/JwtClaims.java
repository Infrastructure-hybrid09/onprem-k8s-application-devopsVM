package com.neuroplan.auth.session;

public record JwtClaims(long userId, String tokenId) {
}
