package org.nowstart.coderouter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record OpenAiErrorResponse(
        ErrorBody error
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            String message,
            String type,
            String param,
            String code,
            List<ApiErrorDetail> details
    ) {
    }
}
