package com.kw.knowone.notification.push;

import java.net.URI;

public interface PushPort {
    Result send(Message message);
    record Message(URI endpoint,String p256dh,String auth,String payload) { }
    record Result(int status,Long retryAfterSeconds) {
        public boolean accepted(){return status>=200&&status<300;}
    }
}
