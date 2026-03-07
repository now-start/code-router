package org.nowstart.router.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CodexAppServerClient implements AutoCloseable {

    private final ObjectMapper objectMapper;
    private final CodeRouterProperties properties;
    private final Duration requestTimeout;
    private final Duration startupTimeout;
    private final ExecutorService ioExecutor;
    private final Map<String, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CodexNotificationListener> notificationListeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleMonitor = new Object();
    private final Object writeMonitor = new Object();

    private volatile Process process;
    private volatile BufferedWriter stdin;

    public CodexAppServerClient(ObjectMapper objectMapper, CodeRouterProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.requestTimeout = properties.getCodex().getRequestTimeout();
        this.startupTimeout = properties.getCodex().getStartupTimeout();
        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public JsonNode request(String method, Object params) {
        ensureStarted();

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingResponses.put(requestId, responseFuture);

        try {
            writeRequest(requestId, method, params);
            return responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            throw new CodexRpcException(null, "Timed out waiting for Codex response.");
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new CodexRpcException(null, "Interrupted while waiting for Codex response.");
        } catch (ExecutionException executionException) {
            throw unwrap(executionException.getCause());
        } catch (CompletionException completionException) {
            throw unwrap(completionException);
        } catch (Exception exception) {
            if (exception instanceof CodexRpcException rpcException) {
                throw rpcException;
            }
            throw new CodexRpcException(null, "Failed to communicate with Codex app-server.");
        } finally {
            pendingResponses.remove(requestId);
        }
    }

    public AutoCloseable addNotificationListener(CodexNotificationListener listener) {
        notificationListeners.add(Objects.requireNonNull(listener));
        return () -> notificationListeners.remove(listener);
    }

    public boolean isAlive() {
        Process currentProcess = process;
        return currentProcess != null && currentProcess.isAlive();
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            failPending(new CodexRpcException(null, "Codex app-server was closed."));
            Process currentProcess = process;
            process = null;
            stdin = null;
            if (currentProcess != null) {
                currentProcess.destroyForcibly();
            }
        }
        ioExecutor.close();
    }

    private void ensureStarted() {
        if (isAlive()) {
            return;
        }

        synchronized (lifecycleMonitor) {
            if (isAlive()) {
                return;
            }

            try {
                List<String> command = new ArrayList<>();
                command.add(properties.getCodex().getCommand());
                command.addAll(properties.getCodex().getArguments());

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.directory(properties.getCodex().getProcessWorkingDirectory().toFile());
                Process startedProcess = processBuilder.start();
                this.process = startedProcess;
                this.stdin = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));

                ioExecutor.submit(() -> readStdout(startedProcess));
                ioExecutor.submit(() -> readStderr(startedProcess));
                startedProcess.onExit().thenRun(this::handleProcessExit);

                JsonNode initializeResult = directRequest("initialize", Map.of(
                        "clientInfo", Map.of("name", "code-router", "version", "0.0.0")
                ), startupTimeout);
                log.debug("Initialized Codex app-server: {}", initializeResult);
            } catch (IOException exception) {
                throw new CodexRpcException(null, "Unable to start Codex app-server process.");
            }
        }
    }

    private JsonNode directRequest(String method, Object params, Duration timeout) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingResponses.put(requestId, responseFuture);
        try {
            writeRequest(requestId, method, params);
            return responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            throw new CodexRpcException(null, "Timed out during Codex app-server startup.");
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new CodexRpcException(null, "Interrupted during Codex app-server startup.");
        } catch (ExecutionException executionException) {
            throw unwrap(executionException.getCause());
        } catch (Exception exception) {
            if (exception instanceof CompletionException completionException) {
                throw unwrap(completionException);
            }
            if (exception instanceof CodexRpcException rpcException) {
                throw rpcException;
            }
            throw new CodexRpcException(null, "Failed during Codex app-server startup.");
        } finally {
            pendingResponses.remove(requestId);
        }
    }

    private void writeRequest(String requestId, String method, Object params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", requestId);
        payload.put("method", method);
        payload.put("params", params == null ? Map.of() : params);

        try {
            String serialized = objectMapper.writeValueAsString(payload);
            synchronized (writeMonitor) {
                BufferedWriter currentWriter = stdin;
                if (currentWriter == null) {
                    throw new CodexRpcException(null, "Codex app-server is not ready.");
                }
                currentWriter.write(serialized);
                currentWriter.newLine();
                currentWriter.flush();
            }
        } catch (IOException exception) {
            throw new CodexRpcException(null, "Failed to write request to Codex app-server.");
        }
    }

    private void readStdout(Process currentProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (IOException exception) {
            if (currentProcess.isAlive()) {
                log.warn("Codex app-server stdout reader failed", exception);
            }
        }
    }

    private void readStderr(Process currentProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("codex stderr: {}", line);
            }
        } catch (IOException exception) {
            if (currentProcess.isAlive()) {
                log.warn("Codex app-server stderr reader failed", exception);
            }
        }
    }

    private void handleMessage(String line) {
        JsonNode message;
        try {
            message = objectMapper.readTree(line);
        } catch (Exception exception) {
            log.debug("Ignoring non-JSON Codex output: {}", line);
            return;
        }

        JsonNode idNode = message.get("id");
        if (idNode != null && (message.has("result") || message.has("error"))) {
            String requestId = idNode.asText();
            CompletableFuture<JsonNode> responseFuture = pendingResponses.get(requestId);
            if (responseFuture == null) {
                return;
            }

            if (message.has("error")) {
                JsonNode errorNode = message.get("error");
                Integer code = errorNode.has("code") ? errorNode.get("code").asInt() : null;
                String errorMessage = errorNode.has("message")
                        ? errorNode.get("message").asText()
                        : "Codex request failed.";
                responseFuture.completeExceptionally(new CodexRpcException(code, errorMessage));
            } else {
                responseFuture.complete(message.get("result"));
            }
            return;
        }

        if (!message.has("method")) {
            return;
        }

        String method = message.get("method").asText();
        JsonNode params = message.path("params");
        for (CodexNotificationListener listener : notificationListeners) {
            try {
                listener.onNotification(method, params);
            } catch (Exception exception) {
                log.warn("Failed to handle Codex notification {}", method, exception);
            }
        }
    }

    private void handleProcessExit() {
        synchronized (lifecycleMonitor) {
            process = null;
            stdin = null;
        }
        failPending(new CodexRpcException(null, "Codex app-server exited unexpectedly."));
    }

    private void failPending(CodexRpcException exception) {
        pendingResponses.values().forEach(future -> future.completeExceptionally(exception));
        pendingResponses.clear();
    }

    private CodexRpcException unwrap(CompletionException completionException) {
        return unwrap(completionException.getCause());
    }

    private CodexRpcException unwrap(Throwable cause) {
        if (cause instanceof CodexRpcException rpcException) {
            return rpcException;
        }
        return new CodexRpcException(null, "Codex request failed.");
    }
}
