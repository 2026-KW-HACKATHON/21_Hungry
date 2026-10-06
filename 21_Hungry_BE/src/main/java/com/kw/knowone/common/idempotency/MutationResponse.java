package com.kw.knowone.common.idempotency;

public record MutationResponse(int status, Object body) {
}
