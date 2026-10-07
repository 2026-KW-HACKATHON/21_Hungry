package com.kw.knowone.notification.service;

import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.notification.push.PushProperties;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationScheduleService {
    private final GroupEventRepository events;private final Clock clock;private final PushProperties properties;
    public NotificationScheduleService(GroupEventRepository events,Clock clock,PushProperties properties){this.events=events;this.clock=clock;this.properties=properties;}
    @Scheduled(cron="0 * * * * *",zone="Asia/Seoul")
    @Transactional
    public void reconcile(){if(!properties.eventWorkerEnabled())return;var now=clock.instant();events.syncAllUpcomingNotifications(now);
        if(digestWindow(now))events.createDailyDigests(now);}
    public static boolean digestWindow(java.time.Instant now){return !now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().isBefore(LocalTime.of(9,0));}
}
