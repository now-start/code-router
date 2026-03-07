package org.nowstart.router.service;

public record ChatExecutionResult(
        String completionId,
        long created,
        String model,
        String content
) {
}
