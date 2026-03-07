package org.nowstart.router.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.nowstart.router.infrastructure.codex.CodexAppServerClient;
import org.nowstart.coderouter.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CodexChatService {

    private final CodexAppServerClient client;
    private final CodexAccountService accountService;
    private final CodeRouterProperties properties;

    public CodexChatService(
            CodexAppServerClient client,
            CodexAccountService accountService,
            CodeRouterProperties properties
    ) {
        this.client = client;
        this.accountService = accountService;
        this.properties = properties;
    }

    public ChatExecutionResult complete(String transcript) {
        return execute(transcript, delta -> {
        });
    }

    public ChatExecutionResult stream(String transcript, Consumer<String> deltaConsumer) {
        return execute(transcript, deltaConsumer);
    }

    private ChatExecutionResult execute(String transcript, Consumer<String> deltaConsumer) {
        accountService.ensureLoggedIn();

        ChatRun chatRun = new ChatRun(properties.getModelAlias(), deltaConsumer);
        AutoCloseable registration = client.addNotificationListener(chatRun::onNotification);

        try {
            JsonNode threadResult = client.request("thread/start", threadStartParams());
            String threadId = requireText(threadResult.path("thread").path("id"), "thread.id");
            chatRun.setThreadId(threadId);

            JsonNode turnResult = client.request("turn/start", turnStartParams(threadId, transcript));
            String turnId = requireText(turnResult.path("turn").path("id"), "turn.id");
            chatRun.setTurnId(turnId);

            return chatRun.await(properties.getCodex().getRequestTimeout());
        } catch (TimeoutException timeoutException) {
            chatRun.interruptIfPossible(client);
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "codex_timeout",
                    "timeout_error",
                    "Codex did not finish the request in time."
            );
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            chatRun.interruptIfPossible(client);
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "codex_interrupted",
                    "server_error",
                    "Codex execution was interrupted."
            );
        } catch (ExecutionException executionException) {
            chatRun.interruptIfPossible(client);
            Throwable cause = executionException.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "codex_execution_failed",
                    "server_error",
                    cause == null ? "Codex execution failed." : cause.getMessage()
            );
        } finally {
            try {
                registration.close();
            } catch (Exception ignored) {
                // Nothing useful to do here.
            }
        }
    }

    private Map<String, Object> threadStartParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cwd", properties.getCodex().getWorkdir().toString());
        params.put("sandbox", sandboxMode(properties.getCodex().getSandboxPolicy()));
        params.put("approvalPolicy", properties.getCodex().getApprovalPolicy());
        params.put("ephemeral", properties.getCodex().isEphemeral());
        params.put("model", properties.getCodex().getModel());
        params.put("personality", properties.getCodex().getPersonality());
        params.put("baseInstructions", properties.getCodex().getBaseInstructions());
        params.put("developerInstructions", properties.getCodex().getDeveloperInstructions());
        return params;
    }

    private Map<String, Object> turnStartParams(String threadId, String transcript) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("threadId", threadId);
        params.put("input", List.of(Map.of("type", "text", "text", transcript)));
        params.put("cwd", properties.getCodex().getWorkdir().toString());
        params.put("approvalPolicy", properties.getCodex().getApprovalPolicy());
        params.put("sandboxPolicy", properties.getCodex().getSandboxPolicy());
        params.put("personality", properties.getCodex().getPersonality());
        return params;
    }

    private String sandboxMode(Map<String, Object> sandboxPolicy) {
        Object type = sandboxPolicy.get("type");
        if ("workspaceWrite".equals(type)) {
            return "workspace-write";
        }
        if ("dangerFullAccess".equals(type)) {
            return "danger-full-access";
        }
        return "read-only";
    }

    private String requireText(JsonNode node, String fieldName) {
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "codex_protocol_error",
                    "server_error",
                    "Codex response is missing " + fieldName + "."
            );
        }
        return value;
    }

    private static class ChatRun {

        private final String modelAlias;
        private final Consumer<String> deltaConsumer;
        private final CompletableFuture<ChatExecutionResult> completion = new CompletableFuture<>();
        private final StringBuilder streamedText = new StringBuilder();

        private volatile String finalText;
        private volatile String threadId;
        private volatile String turnId;
        private volatile long created = Instant.now().getEpochSecond();

        private ChatRun(String modelAlias, Consumer<String> deltaConsumer) {
            this.modelAlias = modelAlias;
            this.deltaConsumer = Objects.requireNonNull(deltaConsumer);
        }

        private void setThreadId(String threadId) {
            this.threadId = threadId;
        }

        private void setTurnId(String turnId) {
            this.turnId = turnId;
        }

        private void onNotification(String method, JsonNode params) {
            if (completion.isDone()) {
                return;
            }

            if (!matchesThread(params)) {
                return;
            }

            switch (method) {
                case "agentMessageDelta", "item/agentMessage/delta" -> handleDelta(params.path("delta").asText(""));
                case "item/completed" -> handleCompletedItem(params.path("item"));
                case "turn/completed" -> handleTurnCompleted(params.path("turn"));
                default -> {
                    // Ignore unrelated notifications.
                }
            }
        }

        private boolean matchesThread(JsonNode params) {
            if (threadId == null) {
                return false;
            }
            String notificationThreadId = params.path("threadId").asText(null);
            return threadId.equals(notificationThreadId);
        }

        private void handleDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }

            streamedText.append(delta);
            try {
                deltaConsumer.accept(delta);
            } catch (RuntimeException runtimeException) {
                completion.completeExceptionally(runtimeException);
            }
        }

        private void handleCompletedItem(JsonNode item) {
            if (!"agentMessage".equals(item.path("type").asText())) {
                return;
            }
            finalText = item.path("text").asText(null);
        }

        private void handleTurnCompleted(JsonNode turn) {
            if (turnId != null && !turnId.equals(turn.path("id").asText(null))) {
                return;
            }

            created = Instant.now().getEpochSecond();
            String status = turn.path("status").asText();
            if ("failed".equalsIgnoreCase(status)) {
                JsonNode errorNode = turn.path("error");
                completion.completeExceptionally(mapError(errorNode));
                return;
            }

            String content = finalText != null ? finalText : streamedText.toString();
            completion.complete(new ChatExecutionResult(
                    "chatcmpl-" + (turnId == null ? "unknown" : turnId),
                    created,
                    modelAlias,
                    content
            ));
        }

        private ApiException mapError(JsonNode errorNode) {
            String message = errorNode.path("message").asText("Codex execution failed.");
            String codexCode = errorNode.path("codexErrorInfo").asText("other");
            return switch (codexCode) {
                case "usageLimitExceeded" -> new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "usage_limit_exceeded",
                        "rate_limit_error",
                        message
                );
                case "serverOverloaded" -> new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "codex_overloaded",
                        "service_unavailable_error",
                        message
                );
                case "unauthorized" -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "codex_unauthorized",
                        "authentication_error",
                        message
                );
                default -> new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "codex_execution_failed",
                        "server_error",
                        message
                );
            };
        }

        private ChatExecutionResult await(Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            return completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void interruptIfPossible(CodexAppServerClient client) {
            if (threadId == null || turnId == null || completion.isDone()) {
                return;
            }
            try {
                client.request("turn/interrupt", Map.of("threadId", threadId, "turnId", turnId));
            } catch (RuntimeException ignored) {
                // Best effort interruption only.
            }
        }
    }
}
