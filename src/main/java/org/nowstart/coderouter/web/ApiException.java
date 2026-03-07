package org.nowstart.coderouter.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;

public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final String type;
    private final String param;
    private final List<ApiErrorDetail> details;
    private final Map<String, String> headers;

    public ApiException(HttpStatusCode status, String code, String type, String message) {
        this(status, code, type, message, null, List.of(), Map.of());
    }

    public ApiException(HttpStatusCode status, String code, String type, String message, String param) {
        this(status, code, type, message, param, List.of(), Map.of());
    }

    public ApiException(
            HttpStatusCode status,
            String code,
            String type,
            String message,
            String param,
            List<ApiErrorDetail> details,
            Map<String, String> headers
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.type = type;
        this.param = param;
        this.details = details == null ? List.of() : List.copyOf(details);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getType() {
        return type;
    }

    public String getParam() {
        return param;
    }

    public List<ApiErrorDetail> getDetails() {
        return details;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
