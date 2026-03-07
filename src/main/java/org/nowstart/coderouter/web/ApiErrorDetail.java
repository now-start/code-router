package org.nowstart.coderouter.web;

public record ApiErrorDetail(
        String field,
        String message,
        String code
) {
}
