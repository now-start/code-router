package org.nowstart.router.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.nowstart.router.infrastructure.codex.CodexAppServerClient;
import org.nowstart.coderouter.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CodexAccountService {

    private final CodexAppServerClient client;
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    public CodexAccountService(CodexAppServerClient client) {
        this.client = client;
    }

    @PostConstruct
    void subscribeToNotifications() {
        client.addNotificationListener(this::handleNotification);
    }

    public CodexAccountStatus getAccountStatus() {
        JsonNode response = client.request("account/read", Map.of());
        JsonNode accountNode = response.path("account");
        boolean loggedIn = !accountNode.isMissingNode() && !accountNode.isNull();
        return new CodexAccountStatus(
                loggedIn,
                response.path("requiresOpenaiAuth").asBoolean(false),
                loggedIn ? accountNode.path("type").asText(null) : null,
                loggedIn ? accountNode.path("email").asText(null) : null,
                loggedIn ? accountNode.path("planType").asText(null) : null
        );
    }

    public void ensureLoggedIn() {
        if (!getAccountStatus().loggedIn()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "codex_not_logged_in",
                    "service_unavailable_error",
                    "Codex is not logged in with a ChatGPT account."
            );
        }
    }

    public LoginAttempt startLogin() {
        JsonNode response = client.request("account/login/start", Map.of("type", "chatgpt"));
        LoginAttempt attempt = new LoginAttempt(
                response.path("loginId").asText(),
                response.path("authUrl").asText(),
                "pending",
                null
        );
        loginAttempts.put(attempt.loginId(), attempt);
        return attempt;
    }

    public LoginAttempt getLoginAttempt(String loginId) {
        LoginAttempt attempt = loginAttempts.get(loginId);
        if (attempt == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "login_not_found",
                    "invalid_request_error",
                    "Login attempt was not found."
            );
        }
        return attempt;
    }

    public LoginAttempt cancelLogin(String loginId) {
        JsonNode response = client.request("account/login/cancel", Map.of("loginId", loginId));
        String status = response.path("status").asText();
        if ("notFound".equals(status)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "login_not_found",
                    "invalid_request_error",
                    "Login attempt was not found."
            );
        }

        LoginAttempt updated = new LoginAttempt(loginId, getLoginAttempt(loginId).authUrl(), "cancelled", null);
        loginAttempts.put(loginId, updated);
        return updated;
    }

    public void logout() {
        client.request("account/logout", Map.of());
    }

    private void handleNotification(String method, JsonNode params) {
        if (!"account/login/completed".equals(method)) {
            return;
        }

        String loginId = params.path("loginId").asText(null);
        if (loginId == null) {
            return;
        }

        boolean success = params.path("success").asBoolean(false);
        LoginAttempt previous = loginAttempts.get(loginId);
        if (previous == null) {
            return;
        }

        LoginAttempt updated = new LoginAttempt(
                loginId,
                previous.authUrl(),
                success ? "completed" : "failed",
                params.path("error").isNull() ? null : params.path("error").asText()
        );
        loginAttempts.put(loginId, updated);
    }
}
