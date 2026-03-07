package org.nowstart.router.web.admin;

import org.nowstart.router.service.CodexAccountService;
import org.nowstart.router.service.CodexAccountStatus;
import org.nowstart.router.service.LoginAttempt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/codex/account")
public class CodexAdminController {

    private final CodexAccountService accountService;

    public CodexAdminController(CodexAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public AdminCodexResponses.AccountResponse getAccount() {
        CodexAccountStatus status = accountService.getAccountStatus();
        AdminCodexResponses.Account account = status.loggedIn()
                ? new AdminCodexResponses.Account(status.type(), status.email(), status.planType())
                : null;
        return new AdminCodexResponses.AccountResponse(status.loggedIn(), status.requiresOpenaiAuth(), account);
    }

    @PostMapping("/login")
    public AdminCodexResponses.LoginResponse startLogin() {
        return toLoginResponse(accountService.startLogin());
    }

    @GetMapping("/login/{loginId}")
    public AdminCodexResponses.LoginResponse getLogin(@PathVariable String loginId) {
        return toLoginResponse(accountService.getLoginAttempt(loginId));
    }

    @DeleteMapping("/login/{loginId}")
    public AdminCodexResponses.LoginResponse cancelLogin(@PathVariable String loginId) {
        return toLoginResponse(accountService.cancelLogin(loginId));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout() {
        accountService.logout();
        return ResponseEntity.noContent().build();
    }

    private AdminCodexResponses.LoginResponse toLoginResponse(LoginAttempt loginAttempt) {
        return new AdminCodexResponses.LoginResponse(
                loginAttempt.loginId(),
                loginAttempt.authUrl(),
                loginAttempt.status(),
                loginAttempt.error()
        );
    }
}
