package org.nowstart.router.web.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        Object tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("response_format") Object responseFormat,
        Object metadata
) {

    public boolean streamEnabled() {
        return Boolean.TRUE.equals(stream);
    }
}
