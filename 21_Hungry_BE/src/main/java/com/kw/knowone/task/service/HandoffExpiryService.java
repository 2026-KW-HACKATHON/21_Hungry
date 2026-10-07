package com.kw.knowone.task.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import com.kw.knowone.task.entity.TaskModels.Handoff;
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import com.kw.knowone.task.repository.TaskRepository;

@Service
public class HandoffExpiryService {
    private final TaskRepository repository;
    private final ScheduleMutationService mutations;
    private final Clock clock;
    private final GroupEventRepository events;
    public HandoffExpiryService(TaskRepository repository,ScheduleMutationService mutations,Clock clock,GroupEventRepository events){
        this.repository=repository;this.mutations=mutations;this.clock=clock;this.events=events;
    }
    public void expireIfOverdue(UUID handoffId){
        mutations.executeSystem(()->{
            Handoff handoff=repository.findHandoff(handoffId).orElse(null);if(handoff==null||!"OPEN".equals(handoff.status()))return;
            Occurrence occurrence=repository.findOccurrence(handoff.occurrenceId()).orElse(null);Instant now=clock.instant();
            if(occurrence!=null&&!occurrence.endsAt().isAfter(now))expire(handoff,occurrence,now);
        });
    }
    public void expireOccurrenceIfOverdue(UUID occurrenceId){repository.findOpenHandoff(occurrenceId).ifPresent(value->expireIfOverdue(value.id()));}
    public void expireGroup(UUID groupId){mutations.executeSystem(()->{Instant now=clock.instant();for(Handoff handoff:repository.findOverdueOpenHandoffs(groupId,now)){
        Occurrence occurrence=repository.findOccurrence(handoff.occurrenceId()).orElseThrow();expire(handoff,occurrence,now);}});}
    private void expire(Handoff handoff,Occurrence occurrence,Instant now){
        if(repository.expireOpenHandoff(handoff.id(),handoff.version(),now)!=1)return;
        repository.cancelPendingNotifications(occurrence.id());repository.cancelPendingDeliveries(occurrence.id());
        events.audit(occurrence.groupId(),null,"HANDOFF_EXPIRED","HANDOFF_REQUEST",handoff.id(),
                java.util.Map.of("status","OPEN","version",handoff.version()),java.util.Map.of("status","EXPIRED","version",handoff.version()+1),UUID.randomUUID());
        events.taskNotification(occurrence.groupId(),"HANDOFF_EXPIRED","handoff-expired:"+handoff.id(),occurrence.id(),handoff.id(),null,occurrence.version(),java.util.Map.of("schemaVersion",1),now);
    }
}
