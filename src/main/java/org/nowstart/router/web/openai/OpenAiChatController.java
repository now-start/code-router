package org.nowstart.router.web.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.nowstart.router.service.ChatExecutionCoordinator;
import org.nowstart.router.service.ChatExecutionResult;
import org.nowstart.router.service.ChatRequestSupport;
import org.nowstart.router.service.CodexAccountService;
import org.nowstart.router.service.CodexChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1")
public class OpenAiChatController {

    private final CodeRouterProperties properties;
    private final ChatRequestSupport chatRequestSupport;
    private final ChatExecutionCoordinator executionCoordinator;
    private final CodexAccountService accountService;
    private final CodexChatService codexChatService;
    private final ExecutorService chatStreamingExecutor;
    private final ObjectMapper objectMapper;

    public OpenAiChatController(
            CodeRouterProperties properties,
            ChatRequestSupport chatRequestSupport,
            ChatExecutionCoordinator executionCoordinator,
            CodexAccountService accountService,
            CodexChatService codexChatService,
            ExecutorService chatStreamingExecutor,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.chatRequestSupport = chatRequestSupport;
        this.executionCoordinator = executionCoordinator;
        this.accountService = accountService;
        this.codexChatService = codexChatService;
        this.chatStreamingExecutor = chatStreamingExecutor;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/models")
    public ModelListResponse listModels() {
        return new ModelListResponse(
                "list",
                java.util.List.of(new ModelListResponse.ModelDescriptor(
                        properties.getModelAlias(),
                        "model",
                        0L,
                        "code-router"
                ))
        );
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<?> createChatCompletion(@RequestBody ChatCompletionRequest request) {
        String transcript = chatRequestSupport.validateAndBuildTranscript(request);

        if (request.streamEnabled()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(streamCompletion(transcript));
        }

        try (ChatExecutionCoordinator.Lease ignored = executionCoordinator.acquire()) {
            accountService.ensureLoggedIn();
            ChatExecutionResult result = codexChatService.complete(transcript);
            return ResponseEntity.ok(new ChatCompletionResponse(
                    result.completionId(),
                    "chat.completion",
                    result.created(),
                    result.model(),
                    java.util.List.of(new ChatCompletionResponse.Choice(
                            0,
                            new ChatCompletionResponse.Message("assistant", result.content()),
                            "stop"
                    ))
            ));
        }
    }

    private SseEmitter streamCompletion(String transcript) {
        ChatExecutionCoordinator.Lease lease = executionCoordinator.acquire();
        accountService.ensureLoggedIn();

        SseEmitter emitter = new SseEmitter(properties.getCodex().getRequestTimeout().toMillis());
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                lease.close();
            }
        };

        emitter.onCompletion(release);
        emitter.onTimeout(() -> {
            release.run();
            emitter.complete();
        });
        emitter.onError(error -> release.run());

        chatStreamingExecutor.submit(() -> {
            try {
                String completionId = "chatcmpl-" + java.util.UUID.randomUUID();
                long created = System.currentTimeMillis() / 1000L;
                sendChunk(emitter, createRoleChunk(completionId, created));
                codexChatService.stream(transcript, delta -> sendChunk(
                        emitter,
                        createDeltaChunk(completionId, created, delta)
                ));
                sendChunk(emitter, createStopChunk(completionId, created));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            } finally {
                release.run();
            }
        });

        return emitter;
    }

    private void sendChunk(SseEmitter emitter, ObjectNode payload) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to stream SSE chunk.", ioException);
        }
    }

    private ObjectNode createRoleChunk(String completionId, long created) {
        ObjectNode payload = baseChunk(completionId, created);
        ArrayNode choices = payload.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        choice.putNull("finish_reason");
        return payload;
    }

    private ObjectNode createDeltaChunk(String completionId, long created, String deltaText) {
        ObjectNode payload = baseChunk(completionId, created);
        ArrayNode choices = payload.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("content", deltaText);
        choice.putNull("finish_reason");
        return payload;
    }

    private ObjectNode createStopChunk(String completionId, long created) {
        ObjectNode payload = baseChunk(completionId, created);
        ArrayNode choices = payload.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", "stop");
        return payload;
    }

    private ObjectNode baseChunk(String completionId, long created) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", completionId);
        payload.put("object", "chat.completion.chunk");
        payload.put("created", created);
        payload.put("model", properties.getModelAlias());
        return payload;
    }
}
