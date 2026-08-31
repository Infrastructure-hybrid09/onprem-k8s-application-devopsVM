package com.neuroplan.auth.auth.dto;

import java.time.Instant;

public record ReauthResponse(Instant expiresAt) {}
