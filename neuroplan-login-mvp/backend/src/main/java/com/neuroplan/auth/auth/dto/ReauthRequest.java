package com.neuroplan.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReauthRequest(
        @NotBlank @Size(min = 8, max = 72) String password
) {}
