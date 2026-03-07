package org.nowstart.router.web.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ChatMessage(
        String role,
        Object content
) {
}
