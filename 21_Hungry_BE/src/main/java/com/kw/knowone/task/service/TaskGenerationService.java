package com.kw.knowone.task.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import com.kw.knowone.task.entity.TaskModels.Series;
import com.kw.knowone.task.repository.TaskRepository;

@Service
public class TaskGenerationService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final TaskRepository repository;
    private final ScheduleAssignmentService assignments;
    private final GroupEventRepository events;
    private final ScheduleMutationService mutations;
    private final Clock clock;

    public TaskGenerationService(TaskRepository repository,ScheduleAssignmentService assignments,
            GroupEventRepository events,ScheduleMutationService mutations,Clock clock){
        this.repository=repository;this.assignments=assignments;this.events=events;this.mutations=mutations;this.clock=clock;
    }

    @Scheduled(cron="0 5 0 * * *",zone="Asia/Seoul")
    public void scheduledGenerate(){mutations.executeSystem(()->generateLocked(repository.findGeneratableSeries(),UUID.randomUUID()));}

    public List<UUID> generateSeriesLocked(UUID seriesId,UUID requestId){
        Series series=repository.findSeries(seriesId).orElseThrow();
        return generateLocked(List.of(series),requestId);
    }

    public List<UUID> generateAllLocked(UUID requestId){
        return generateLocked(repository.findGeneratableSeries(),requestId);
    }

    private List<UUID> generateLocked(List<Series> seriesValues,UUID requestId){
        Instant now=clock.instant();LocalDate today=LocalDate.now(clock);LocalDate horizon=today.plusDays(14);
        List<Occurrence> created=new ArrayList<>();
        for(Series series:seriesValues){
            LocalDate from=series.firstDate().isAfter(today)?series.firstDate():today;
            LocalDate until="ONCE".equals(series.recurrence())?series.firstDate().plusDays(1):
                    series.lastDate()==null?horizon:series.lastDate().plusDays(1).isBefore(horizon)?series.lastDate().plusDays(1):horizon;
            for(LocalDate date=from;date.isBefore(until);date=date.plusDays(1)){
                if(series.stopFromDate()!=null&&!date.isBefore(series.stopFromDate()))break;
                if(!occurs(series,date))continue;
                List<UUID> medications="MEDICATION".equals(series.kind())
                        ?repository.activeSeriesMedicationIds(series.id(),series.currentRevisionNo(),date):List.of();
                if("MEDICATION".equals(series.kind())&&medications.isEmpty())continue;
                Instant starts=date.atTime(series.localTime()).atZone(KST).toInstant();
                if(starts.isBefore(now))continue;
                Instant ends=starts.plus(Duration.ofMinutes(series.durationMinutes()));
                UUID id=repository.insertOccurrenceIfAbsent(series.groupId(),series.id(),series.currentRevisionNo(),date,
                        series.title(),series.description(),starts,ends);
                if(id!=null){repository.snapshotOccurrenceMedications(series.groupId(),id,medications);created.add(repository.findOccurrence(id).orElseThrow());}
            }
        }
        List<ScheduleAssignmentService.AssignmentDecision> decisions=assignments.assignNew(created);
        for(ScheduleAssignmentService.AssignmentDecision decision:decisions)if(decision.bundled())events.audit(
                decision.groupId(),null,"TASK_ASSIGNMENT_BUNDLED","TASK_OCCURRENCE",decision.occurrenceIds().getFirst(),null,
                Map.of("reason","FOUR_HOUR_BUNDLE","occurrenceIds",decision.occurrenceIds(),"assigneeUserId",decision.assigneeUserId()),requestId);
        for(Occurrence initial:created){
            Occurrence value=repository.findOccurrence(initial.id()).orElseThrow();
            UUID handoff=null;
            if(value.assigneeUserId()==null)handoff=repository.openNoCandidate(value.groupId(),value.id());
            events.audit(value.groupId(),repository.findSeries(value.seriesId()).orElseThrow().createdBy(),"TASK_CREATED",
                    "TASK_OCCURRENCE",value.id(),null,event(value),requestId);
            if(handoff==null)events.taskNotification(value.groupId(),"TASK_ASSIGNED","task:"+value.id()+":assignment:"+value.version(),
                    value.id(),null,value.assigneeUserId(),value.version(),Map.of("schemaVersion",1),now);
            events.syncOccurrenceNotifications(value.id(),now);
        }
        return created.stream().map(Occurrence::id).toList();
    }

    private boolean occurs(Series series,LocalDate date){
        return switch(series.recurrence()){
            case "ONCE"->date.equals(series.firstDate());
            case "DAILY"->true;
            case "WEEKLY"->series.weekdays().contains(date.getDayOfWeek().getValue());
            default->false;
        };
    }
    private Map<String,Object> event(Occurrence value){return Map.of("executionStatus",value.status(),
            "assigneeUserId",value.assigneeUserId()==null?"":value.assigneeUserId().toString(),
            "assignmentOrigin",value.assignmentOrigin()==null?"":value.assignmentOrigin(),"version",value.version());}
}
