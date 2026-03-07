package org.nowstart.router.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.nowstart.coderouter.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChatExecutionCoordinator {

    private final Semaphore capacityLimiter;
    private final Semaphore executionLimiter;
    private final Duration maxWait;

    public ChatExecutionCoordinator(CodeRouterProperties properties) {
        int queueCapacity = Math.max(properties.getQueue().getCapacity(), 0);
        this.capacityLimiter = new Semaphore(queueCapacity + 1, true);
        this.executionLimiter = new Semaphore(1, true);
        this.maxWait = properties.getQueue().getMaxWait();
    }

    public Lease acquire() {
        if (!capacityLimiter.tryAcquire()) {
            throw busyException();
        }

        boolean acquiredExecution = false;
        try {
            acquiredExecution = executionLimiter.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquiredExecution) {
                throw busyException();
            }
            return new Lease(this);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw busyException();
        } catch (RuntimeException runtimeException) {
            if (!acquiredExecution) {
                capacityLimiter.release();
            }
            throw runtimeException;
        }
    }

    private ApiException busyException() {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "busy",
                "rate_limit_error",
                "The Codex router is busy. Retry later.",
                null,
                null,
                Map.of("Retry-After", Long.toString(Math.max(maxWait.toSeconds(), 1)))
        );
    }

    private void release() {
        executionLimiter.release();
        capacityLimiter.release();
    }

    public record Lease(ChatExecutionCoordinator coordinator) implements AutoCloseable {

        @Override
        public void close() {
            coordinator.release();
        }
    }
}
