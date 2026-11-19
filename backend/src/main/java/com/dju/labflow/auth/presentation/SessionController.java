package com.dju.labflow.auth.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.user.application.UserService;
import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HttpOnly 쿠키 세션의 현재 사용자만 반환한다. */
@RestController
public class SessionController {
    private final UserService users;

    public SessionController(UserService users) {
        this.users = users;
    }

    public record Session(Long userId, String name, String email, Role role) {}

    @GetMapping("/api/v1/auth/me")
    @Operation(summary = "현재 로그인 사용자")
    public ResponseEntity<Session> me(@Parameter(hidden = true) @CurrentUserId UserId id) {
        var user = users.get(id);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new Session(id.value(), user.getName(), user.getEmail(), user.getRole()));
    }
}
