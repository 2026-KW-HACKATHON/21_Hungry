package com.kw.knowone.notification.push;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification")
public record PushProperties(boolean eventWorkerEnabled, boolean pushWorkerEnabled, int workerConcurrency,
        Duration lease, int maxAttempts, Duration pollDelay, Duration pushTimeout, String vapidPublicKey,
        String vapidPrivateKey, String vapidSubject, List<String> allowedHostSuffixes) {
    public boolean configured() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank() && vapidPrivateKey != null
                && !vapidPrivateKey.isBlank() && vapidSubject != null && !vapidSubject.isBlank();
    }
}
