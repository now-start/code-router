package org.nowstart.router.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.nowstart.router.web.openai.ChatCompletionRequest;
import org.nowstart.router.web.openai.ChatMessage;
import org.nowstart.coderouter.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChatRequestSupport {

    private final CodeRouterProperties properties;

    public ChatRequestSupport(CodeRouterProperties properties) {
        this.properties = properties;
    }

    public String validateAndBuildTranscript(ChatCompletionRequest request) {
        if (request == null) {
            throw invalid("Request body is required.", null, "invalid_request");
        }
        if (!properties.getModelAlias().equals(request.model())) {
            throw invalid("Unsupported model.", "model", "invalid_model");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw invalid("At least one message is required.", "messages", "invalid_request");
        }
        if (isPresent(request.tools())) {
            throw unsupported("tools");
        }
        if (isPresent(request.toolChoice())) {
            throw unsupported("tool_choice");
        }
        if (isPresent(request.responseFormat())) {
            throw unsupported("response_format");
        }
        if (request.maxTokens() != null) {
            throw unsupported("max_tokens");
        }
        if (request.temperature() != null && Double.compare(request.temperature(), 1.0d) != 0) {
            throw unsupported("temperature");
        }

        List<String> transcriptLines = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            transcriptLines.add(formatTranscriptLine(message));
        }

        return String.join("\n\n", transcriptLines);
    }

    private String formatTranscriptLine(ChatMessage message) {
        if (message == null) {
            throw invalid("Messages must not contain null entries.", "messages", "invalid_request");
        }

        String role = message.role();
        if (!List.of("system", "user", "assistant").contains(role)) {
            throw invalid("Unsupported message role.", "messages.role", "invalid_request");
        }

        String content = extractText(message.content());
        if (content.isBlank()) {
            throw invalid("Message content must not be blank.", "messages.content", "invalid_request");
        }

        return switch (role) {
            case "system" -> "System: " + content;
            case "assistant" -> "Assistant: " + content;
            default -> "User: " + content;
        };
    }

    private String extractText(Object content) {
        if (content == null) {
            throw invalid("Message content is required.", "messages.content", "invalid_request");
        }

        if (content instanceof String text) {
            return text;
        }

        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> partMap)) {
                    throw invalid("Message content must be a string or text part array.", "messages.content", "invalid_request");
                }

                String type = stringValue(partMap.get("type"));
                if (!"text".equals(type)) {
                    throw unsupported("messages.content.type");
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(stringValue(partMap.get("text")));
            }
            return builder.toString();
        }

        throw invalid("Message content must be a string or text part array.", "messages.content", "invalid_request");
    }

    private boolean isPresent(Object value) {
        return value != null;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private ApiException unsupported(String param) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unsupported_feature",
                "invalid_request_error",
                "This Codex router only supports text chat completions.",
                param
        );
    }

    private ApiException invalid(String message, String param, String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "invalid_request_error", message, param);
    }
}
