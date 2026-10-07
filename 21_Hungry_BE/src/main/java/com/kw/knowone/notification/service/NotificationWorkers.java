package com.kw.knowone.notification.service;

import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.notification.push.PushEndpointPolicy;
import com.kw.knowone.notification.push.PushPort;
import com.kw.knowone.notification.push.PushProperties;
import com.kw.knowone.notification.repository.NotificationWorkerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationWorkers {
    private final NotificationWorkerRepository repository;private final PushPort push;private final PushEndpointPolicy endpoints;
    private final PushProperties properties;private final Clock clock;private final ObjectMapper json;
    private final ExecutorService deliveryExecutor;
    public NotificationWorkers(NotificationWorkerRepository repository,PushPort push,PushEndpointPolicy endpoints,
            PushProperties properties,Clock clock,ObjectMapper json){this.repository=repository;this.push=push;this.endpoints=endpoints;this.properties=properties;this.clock=clock;this.json=json;
        this.deliveryExecutor=Executors.newFixedThreadPool(batch(),Thread.ofPlatform().daemon().name("push-delivery-",0).factory());
        if(properties.pushWorkerEnabled()&&properties.lease().compareTo(properties.pushTimeout().plusSeconds(10))<=0)
            throw new IllegalStateException("Notification lease must exceed push timeout by more than 10 seconds");}

    @Scheduled(fixedDelayString="${app.notification.poll-delay:2s}")
    public void expand(){if(!properties.eventWorkerEnabled())return;Instant now=clock.instant();for(var claim:repository.claimEvents(batch(),now,properties.lease())){
        try{repository.expand(claim,clock.instant());}catch(RuntimeException failure){repository.eventFailure(claim,clock.instant(),properties.maxAttempts());}}}

    @Scheduled(fixedDelayString="${app.notification.poll-delay:2s}")
    public void deliver(){if(!properties.pushWorkerEnabled())return;Instant now=clock.instant();var futures=repository.claimDeliveries(batch(),now,properties.lease()).stream()
            .map(claim->deliveryExecutor.submit(()->deliverOne(claim))).toList();for(var future:futures)try{future.get();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}catch(Exception ignored){}}
    private void deliverOne(NotificationWorkerRepository.DeliveryClaim claim){
        try{var outbound=repository.prepare(claim,clock.instant());if(outbound.isEmpty())return;var value=outbound.get();
            var uri=endpoints.validate(value.endpoint());String payload=json.writeValueAsString(Map.of(
                    "notificationId",claim.notificationId().toString(),"eventId",value.eventId().toString(),
                    "title",value.title(),"body",value.body(),"tag",value.eventId().toString(),"url","/notifications"));
            PushPort.Result result=push.send(new PushPort.Message(uri,value.p256dh(),value.auth(),payload));
            if(result.accepted())repository.sent(claim,clock.instant(),result.status());
            else repository.deliveryFailure(claim,clock.instant(),result.status(),result.retryAfterSeconds(),properties.maxAttempts(),permanent(result.status()));
        }catch(ApiException invalid){repository.deliveryFailure(claim,clock.instant(),400,null,properties.maxAttempts(),true);}
        catch(RuntimeException failure){repository.deliveryFailure(claim,clock.instant(),0,null,properties.maxAttempts(),false);}}

    @PreDestroy void shutdown(){deliveryExecutor.shutdownNow();}

    private int batch(){return Math.max(1,Math.min(32,properties.workerConcurrency()));}
    private boolean permanent(int status){return status>=300&&status<500&&status!=408&&status!=429&&status!=404&&status!=410;}
}
