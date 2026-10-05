package com.kw.knowone.common.web;

import java.util.Map;

public record ApiErrorResponse(ApiError error) {

    public static ApiErrorResponse of(
            String code,
            String message,
            String requestId,
            Map<String, Object> details) {
        return new ApiErrorResponse(new ApiError(code, message, requestId, details));
    }

    public record ApiError(
            String code,
            String message,
            String requestId,
            Map<String, Object> details) {
    }
}
