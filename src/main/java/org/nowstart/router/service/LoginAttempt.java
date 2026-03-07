package org.nowstart.router.service;

public record LoginAttempt(
        String loginId,
        String authUrl,
        String status,
        String error
) {
}
