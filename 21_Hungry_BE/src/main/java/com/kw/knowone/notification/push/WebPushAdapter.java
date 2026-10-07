package com.kw.knowone.notification.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class WebPushAdapter implements PushPort {
    private final ExposedPushService crypto;private final HttpClient http;private final PushProperties properties;private final Clock clock;
    public WebPushAdapter(PushProperties properties,Clock clock) {
        this.properties=properties;this.clock=clock;
        if(properties.pushWorkerEnabled()&&!properties.configured())throw new IllegalStateException("PUSH_WORKER_ENABLED requires VAPID public/private key and subject");
        try {if(properties.configured()&&Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)==null)Security.addProvider(new BouncyCastleProvider());
            this.crypto=properties.configured()?new ExposedPushService(properties.vapidPublicKey(),properties.vapidPrivateKey(),properties.vapidSubject()):null;}
        catch(Exception e){throw new IllegalStateException("Invalid VAPID configuration",e);}
        this.http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.pushTimeout()).build();
    }
    @Override public Result send(Message message) {
        if(crypto==null)throw new IllegalStateException("Web Push is not configured");
        try {
            Notification notification=new Notification(message.endpoint().toString(),message.p256dh(),message.auth(),
                    message.payload().getBytes(StandardCharsets.UTF_8));
            nl.martijndwars.webpush.HttpRequest encrypted=crypto.prepare(notification);
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(encrypted.getUrl())).timeout(properties.pushTimeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted.getBody()));
            encrypted.getHeaders().forEach((name,value)->{if(!name.equalsIgnoreCase("content-length"))request.header(name,value);});
            HttpResponse<Void> response=http.send(request.build(),HttpResponse.BodyHandlers.discarding());
            return new Result(response.statusCode(),retryAfter(response));
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new PushTransportException(e);}
        catch(Exception e){throw new PushTransportException(e);}
    }
    private Long retryAfter(HttpResponse<?> response){String value=response.headers().firstValue("Retry-After").orElse(null);if(value==null)return null;
        try{return Math.max(0,Long.parseLong(value));}catch(NumberFormatException ignored){try{return Math.max(0,Duration.between(clock.instant(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds());}catch(Exception invalid){return null;}}}
    private static final class ExposedPushService extends AbstractPushService<ExposedPushService>{
        ExposedPushService(String publicKey,String privateKey,String subject)throws Exception{super(publicKey,privateKey,subject);}
        nl.martijndwars.webpush.HttpRequest prepare(Notification value)throws Exception{return prepareRequest(value,Encoding.AES128GCM);}
    }
    public static final class PushTransportException extends RuntimeException { public PushTransportException(Throwable cause){super("Web Push transport failure",cause);} }
}
