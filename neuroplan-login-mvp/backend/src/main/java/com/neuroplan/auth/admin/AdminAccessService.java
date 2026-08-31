package com.neuroplan.auth.admin;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.neuroplan.auth.auth.CurrentUserService;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.user.UserRecord;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {
    private final CurrentUserService currentUserService;
    private final Set<String> adminEmails;

    public AdminAccessService(
            CurrentUserService currentUserService,
            @Value("${app.admin.emails:}") String configuredEmails
    ) {
        this.currentUserService = currentUserService;
        this.adminEmails = Arrays.stream(configuredEmails.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public UserRecord require(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        if (!adminEmails.contains(user.email().toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
        return user;
    }

    public boolean isAdminEmail(String email) {
        return email != null && adminEmails.contains(email.toLowerCase(Locale.ROOT));
    }
}
