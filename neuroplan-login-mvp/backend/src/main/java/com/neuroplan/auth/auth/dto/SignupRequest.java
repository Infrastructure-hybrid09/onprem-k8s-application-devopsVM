package com.neuroplan.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(min = 2, max = 50, message = "닉네임은 2자 이상 50자 이하여야 합니다.")
        @Pattern(
                regexp = "^[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*$",
                message = "닉네임은 문자 또는 숫자로 시작하고 공백, 점, 밑줄, 하이픈만 사용할 수 있습니다."
        )
        String nickname,

        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "올바른 이메일을 입력해 주세요.")
        @Size(max = 190, message = "이메일은 190자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
        String password
) {
}
