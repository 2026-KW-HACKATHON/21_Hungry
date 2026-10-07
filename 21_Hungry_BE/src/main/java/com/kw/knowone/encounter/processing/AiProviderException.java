package com.kw.knowone.encounter.processing;

import java.time.Duration;

public class AiProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final Duration retryAfter;

    public AiProviderException(String code, boolean retryable) { this(code, retryable, null, null); }
    public AiProviderException(String code, boolean retryable, Throwable cause) {
        this(code, retryable, null, cause);
    }
    public AiProviderException(String code, boolean retryable, Duration retryAfter) {
        this(code, retryable, retryAfter, null);
    }
    private AiProviderException(String code, boolean retryable, Duration retryAfter, Throwable cause) {
        super(code, cause);
        this.code=code;
        this.retryable=retryable;
        this.retryAfter=retryAfter;
    }
    public String code(){ return code; }
    public boolean retryable(){ return retryable; }
    public Duration retryAfter(){ return retryAfter; }
}
