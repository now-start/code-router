package org.nowstart.router.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AdminCodexResponses {

    private AdminCodexResponses() {
    }

    public record AccountResponse(
            @JsonProperty("logged_in") boolean loggedIn,
            @JsonProperty("requires_openai_auth") boolean requiresOpenaiAuth,
            Account account
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(
            String type,
            String email,
            @JsonProperty("plan_type") String planType
    ) {
    }

    public record LoginResponse(
            @JsonProperty("login_id") String loginId,
            @JsonProperty("auth_url") String authUrl,
            String status,
            String error
    ) {
    }
}
