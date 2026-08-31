package com.neuroplan.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(
        @NotBlank
        @Size(min = 2, max = 50)
        @Pattern(
                regexp = "^[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*$",
                message = "닉네임은 문자 또는 숫자로 시작하고 공백, 점, 밑줄, 하이픈만 사용할 수 있습니다."
        )
        String nickname
) {}
