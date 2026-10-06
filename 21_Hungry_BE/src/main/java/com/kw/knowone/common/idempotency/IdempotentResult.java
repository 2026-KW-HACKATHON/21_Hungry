package com.kw.knowone.common.idempotency;

public record IdempotentResult(int status, String body, boolean replayed) {
}
