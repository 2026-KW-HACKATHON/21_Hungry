package com.kw.knowone.notification.service;

import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.common.web.CursorService;
import com.kw.knowone.notification.dto.NotificationDtos;
import com.kw.knowone.notification.push.PushEndpointPolicy;
import com.kw.knowone.notification.push.PushProperties;
import com.kw.knowone.notification.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.bouncycastle.jce.ECNamedCurveTable;

@Service
public class NotificationService {
    private final NotificationRepository repository;private final Clock clock;
    private final PushProperties properties;private final PushEndpointPolicy endpoints;
    public NotificationService(NotificationRepository repository,Clock clock,PushProperties properties,
            PushEndpointPolicy endpoints){this.repository=repository;this.clock=clock;this.properties=properties;this.endpoints=endpoints;}

    @Transactional(readOnly=true)
    public NotificationDtos.Page list(UUID userId,UUID groupId,boolean unreadOnly,Integer requested,String cursor){
        int limit=requested==null?20:requested;if(limit<1||limit>100)throw validation("limit은 1~100이어야 합니다.");
        if(groupId!=null&&!repository.activeMembership(groupId,userId))throw notMember();
        List<NotificationDtos.Item> rows=repository.list(userId,groupId,unreadOnly,limit+1,decodeCursor(cursor,groupId,unreadOnly));
        boolean more=rows.size()>limit;List<NotificationDtos.Item> items=more?new ArrayList<>(rows.subList(0,limit)):rows;
        String next=more?encodeCursor(items.getLast(),groupId,unreadOnly):null;
        return new NotificationDtos.Page(List.copyOf(items),next,more,repository.unreadCount(userId,groupId));
    }

    @Transactional
    public NotificationDtos.Item read(UUID userId,UUID id){NotificationRepository.Owner owner=repository.owner(id).orElseThrow(this::notFound);
        if(!owner.userId().equals(userId))throw forbidden();if(!repository.activeMembership(owner.groupId(),userId))throw notMember();
        repository.markRead(id,clock.instant());return repository.item(id).orElseThrow(this::notFound);}

    @Transactional
    public NotificationDtos.Preference preference(UUID userId){NotificationRepository.Preference p=repository.preference(userId);return new NotificationDtos.Preference(p.repeat(),p.version());}

    @Transactional
    public NotificationDtos.Preference updatePreference(UUID userId,NotificationDtos.PreferenceUpdate request){String repeat=request.handoffRepeat().toUpperCase(Locale.ROOT);
        if(!List.of("DAILY","ONCE").contains(repeat))throw validation("handoffRepeat는 DAILY 또는 ONCE여야 합니다.");
        NotificationRepository.Preference current=repository.preference(userId);
        if(current.version()!=request.expectedVersion())throw version(current.version());
        if(!repository.updatePreference(userId,current.version(),repeat))throw version(repository.preference(userId).version());
        NotificationRepository.Preference after=repository.preference(userId);return new NotificationDtos.Preference(after.repeat(),after.version());}

    public NotificationDtos.PushConfig pushConfig(){return properties.pushWorkerEnabled()&&properties.configured()
            ?new NotificationDtos.PushConfig(true,properties.vapidPublicKey()):new NotificationDtos.PushConfig(false,null);}

    @Transactional
    public Created subscribe(UUID userId,NotificationDtos.SubscriptionCreate request){endpoints.validate(request.endpoint());
        byte[] publicKey=decode(request.keys().p256dh(),"p256dh");byte[] auth=decode(request.keys().auth(),"auth");
        if(publicKey.length!=65||publicKey[0]!=4)throw validation("p256dh는 65-byte uncompressed P-256 공개키여야 합니다.");
        try{ECNamedCurveTable.getParameterSpec("prime256v1").getCurve().decodePoint(publicKey).normalize();}
        catch(RuntimeException invalid){throw validation("p256dh가 유효한 P-256 공개키가 아닙니다.");}
        if(auth.length!=16)throw validation("auth는 16-byte secret이어야 합니다.");
        byte[] hash=sha256(request.endpoint());repository.endpointLock(hash);Instant now=clock.instant();
        var active=repository.activeByHash(hash);if(active.isPresent()&&active.get().userId().equals(userId)){
            repository.touch(active.get().id(),request.endpoint(),request.keys().p256dh(),request.keys().auth(),now);
            return new Created(false,new NotificationDtos.SubscriptionCreated(active.get().id(),true));}
        active.ifPresent(value->repository.disable(value.id()));
        UUID id=repository.insertSubscription(userId,request.endpoint(),hash,request.keys().p256dh(),request.keys().auth(),now);
        return new Created(true,new NotificationDtos.SubscriptionCreated(id,true));}

    @Transactional(readOnly=true)
    public NotificationDtos.SubscriptionList subscriptions(UUID userId){return new NotificationDtos.SubscriptionList(repository.subscriptions(userId));}

    @Transactional
    public void unsubscribe(UUID userId,UUID id){var value=repository.subscription(id).orElseThrow(this::notFound);
        if(!value.userId().equals(userId))throw forbidden();if(value.enabled())repository.disable(id);}

    private byte[] decode(String value,String field){try{return Base64.getUrlDecoder().decode(value);}catch(IllegalArgumentException e){throw validation(field+"가 base64url 형식이 아닙니다.");}}
    private byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private String encodeCursor(NotificationDtos.Item item,UUID groupId,boolean unread){String value=item.createdAt().toInstant()+"|"+item.id()+"|"+(groupId==null?"*":groupId)+"|"+unread;return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private CursorService.Value decodeCursor(String cursor,UUID groupId,boolean unread){if(cursor==null||cursor.isBlank())return null;try{String[] p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);String expected=groupId==null?"*":groupId.toString();if(p.length!=4||!p[2].equals(expected)||!p[3].equals(Boolean.toString(unread)))throw new IllegalArgumentException();return new CursorService.Value(Instant.parse(p[0]),UUID.fromString(p[1]));}catch(RuntimeException invalid){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","커서가 올바르지 않습니다.");}}
    private ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","알림 또는 구독을 찾을 수 없습니다.");}
    private ApiException forbidden(){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","이 리소스에 접근할 수 없습니다.");}
    private ApiException notMember(){return new ApiException(HttpStatus.FORBIDDEN,"NOT_MEMBER","공동체의 ACTIVE 구성원이 아닙니다.");}
    private ApiException validation(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private ApiException version(long current){return new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","알림 설정이 변경되었습니다.",Map.of("currentVersion",current));}
    public record Created(boolean created,NotificationDtos.SubscriptionCreated value){}
}
