package org.nowstart.router.web.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices
) {

    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    public record Message(
            String role,
            String content
    ) {
    }
}
