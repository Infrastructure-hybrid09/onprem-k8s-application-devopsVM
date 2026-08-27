package com.neuroplan.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WithdrawRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.")
        String password
) {}
