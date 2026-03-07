package org.nowstart.coderouter.web;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<OpenAiErrorResponse> handleApiException(ApiException exception) {
        HttpHeaders headers = new HttpHeaders();
        exception.getHeaders().forEach(headers::add);
        return new ResponseEntity<>(
                new OpenAiErrorResponse(new OpenAiErrorResponse.ErrorBody(
                        exception.getMessage(),
                        exception.getType(),
                        exception.getParam(),
                        exception.getCode(),
                        exception.getDetails().isEmpty() ? null : exception.getDetails()
                )),
                headers,
                exception.getStatus()
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            UnrecognizedPropertyException.class
    })
    public ResponseEntity<OpenAiErrorResponse> handleBadRequest(Exception exception) {
        String message = "Request body is invalid.";
        if (exception instanceof HttpMessageNotReadableException readable && readable.getMostSpecificCause() != null) {
            message = readable.getMostSpecificCause().getMessage();
        }
        return ResponseEntity.badRequest().body(new OpenAiErrorResponse(
                new OpenAiErrorResponse.ErrorBody(message, "invalid_request_error", null, "invalid_request", null)
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled API exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new OpenAiErrorResponse(
                new OpenAiErrorResponse.ErrorBody(
                        "Unexpected server error.",
                        "server_error",
                        null,
                        "internal_server_error",
                        null
                )
        ));
    }
}
