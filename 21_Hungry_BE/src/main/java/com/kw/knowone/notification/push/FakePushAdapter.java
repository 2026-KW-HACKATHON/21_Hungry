package com.kw.knowone.notification.push;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class FakePushAdapter implements PushPort {
    @Override public Result send(Message message) { return new Result(201,null); }
}
