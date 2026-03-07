package org.nowstart.router.service;

public record CodexAccountStatus(
        boolean loggedIn,
        boolean requiresOpenaiAuth,
        String type,
        String email,
        String planType
) {
}
