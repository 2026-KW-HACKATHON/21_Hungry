package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kw.knowone.common.preview.PreviewTokenService;
import com.kw.knowone.common.web.ApiException;
import tools.jackson.databind.ObjectMapper;

class PreviewTokenServiceTests {
    @Test void injectedClockExpiresPreviewWithoutWaiting(){
        MutableClock clock=new MutableClock(Instant.parse("2026-10-07T00:00:00Z"));
        PreviewTokenService service=new PreviewTokenService(new ObjectMapper(),clock,Duration.ofMinutes(5),
                "test-only-preview-signing-secret-32-bytes");
        UUID user=UUID.randomUUID();String token=service.issue(user,"T08:test","payload","state");
        clock.instant=clock.instant.plus(Duration.ofMinutes(5));
        ApiException error=assertThrows(ApiException.class,()->service.verify(token,user,"T08:test","payload"));
        assertEquals("PREVIEW_STALE",error.code());
    }
    private static final class MutableClock extends Clock{
        private Instant instant;private MutableClock(Instant instant){this.instant=instant;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
    }
}
