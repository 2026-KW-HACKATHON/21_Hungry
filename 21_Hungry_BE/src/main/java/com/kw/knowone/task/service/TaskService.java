package com.kw.knowone.task.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.idempotency.MutationResponse;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.common.web.CursorService;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.group.entity.GroupMember;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.group.repository.GroupRepository;
import com.kw.knowone.task.dto.TaskDtos;
import com.kw.knowone.task.entity.TaskModels.Audit;
import com.kw.knowone.task.entity.TaskModels.Handoff;
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import com.kw.knowone.task.entity.TaskModels.Series;
import com.kw.knowone.task.repository.TaskRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> GENERAL_KINDS = Set.of("HOSPITAL", "EXAM", "PICKUP", "OTHER");
    private final TaskRepository repository;
    private final GroupRepository groups;
    private final GroupEventRepository events;
    private final ScheduleAssignmentService assignments;
    private final ScheduleMutationService mutations;
    private final CursorService cursors;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(TaskRepository repository, GroupRepository groups, GroupEventRepository events,
            ScheduleAssignmentService assignments, ScheduleMutationService mutations, CursorService cursors,
            ObjectMapper objectMapper, Clock clock) {
        this.repository=repository; this.groups=groups; this.events=events; this.assignments=assignments;
        this.mutations=mutations; this.cursors=cursors; this.objectMapper=objectMapper; this.clock=clock;
    }

    public TaskDtos.Page<TaskDtos.Task> list(UUID groupId, UUID userId, Instant from, Instant to,
            String status, UUID assignee, boolean unassigned, boolean overdue, UUID encounterId,
            Integer requestedLimit, String cursor) {
        requireMember(groupId,userId);
        if(from==null||to==null||!from.isBefore(to)||assignee!=null&&unassigned) throw validation("조회 조건이 올바르지 않습니다.");
        if(status!=null&&!Set.of("PENDING","COMPLETED","CANCELED").contains(status)) throw validation("executionStatus가 올바르지 않습니다.");
        if(assignee!=null&&groups.findMembership(groupId,assignee).isEmpty()) throw validation("assigneeUserId는 공동체 구성원이어야 합니다.");
        if(encounterId!=null){UUID actual=repository.findEncounterGroup(encounterId).orElseThrow(this::notFound);
            if(!actual.equals(groupId))throw new ApiException(HttpStatus.FORBIDDEN,"NOT_MEMBER","다른 공동체의 진료 기록입니다.");}
        int limit=limit(requestedLimit); CursorService.Value decoded=cursors.decode(cursor);
        List<Occurrence> rows=repository.list(groupId,from,to,status,assignee,unassigned,overdue,encounterId,limit+1,decoded,clock.instant());
        boolean more=rows.size()>limit; if(more)rows=rows.subList(0,limit);
        String next=more?cursors.encode(rows.getLast().startsAt(),rows.getLast().id()):null;
        return new TaskDtos.Page<>(rows.stream().map(this::dto).toList(),next,more);
    }

    public TaskDtos.Task detail(UUID occurrenceId,UUID userId){Occurrence value=requireOccurrence(occurrenceId);requireMember(value.groupId(),userId);return dto(value);}
    public TaskDtos.Series series(UUID seriesId,UUID userId){Series value=repository.findSeries(seriesId).orElseThrow(this::notFound);requireMember(value.groupId(),userId);
        return new TaskDtos.Series(value.id(),value.groupId(),value.kind(),value.title(),value.description(),rule(value),repository.seriesMedications(value.id(),value.currentRevisionNo()),
                value.stopFromDate(),value.currentRevisionNo(),value.version());}

    public IdempotentResult create(UUID groupId,UUID userId,TaskDtos.CreateRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T03:"+groupId,key,request,()->requireMember(groupId,userId),()->doCreate(groupId,userId,request,key,requestId));
    }
    private MutationResponse doCreate(UUID groupId,UUID userId,TaskDtos.CreateRequest request,String key,UUID requestId){
        validateCreate(request); Instant now=clock.instant(); LocalDate today=LocalDate.now(clock); LocalDate horizon=today.plusDays(14);
        Instant starts=request.rule().firstDate().atTime(request.rule().localTime()).atZone(KST).toInstant();
        Instant ends=starts.plus(Duration.ofMinutes(request.rule().durationMinutes()));
        if(starts.isBefore(now)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION_ERROR","과거 일정은 생성할 수 없습니다.");
        UUID seriesId=repository.insertSeries(groupId,userId,request.kind(),"manual:"+userId+":"+key);
        repository.insertRevision(seriesId,groupId,request.title().trim(),request.description(),request.rule().firstDate(),
                request.rule().localTime(),request.rule().durationMinutes(),userId,now);
        events.audit(groupId,userId,"TASK_SERIES_CREATED","TASK_SERIES",seriesId,null,
                Map.of("kind",request.kind(),"recurrence","ONCE","version",0),requestId);
        List<TaskDtos.Task> occurrences=new ArrayList<>();
        if(!request.rule().firstDate().isBefore(today)&&request.rule().firstDate().isBefore(horizon)){
            UUID assignee=assignments.select(groupId,starts,ends);
            UUID occurrenceId=repository.insertOccurrence(groupId,seriesId,request.rule().firstDate(),request.title().trim(),request.description(),starts,ends,assignee);
            UUID handoff=null; if(assignee==null) handoff=repository.openNoCandidate(groupId,occurrenceId);
            Occurrence created=requireOccurrence(occurrenceId);
            events.audit(groupId,userId,"TASK_CREATED","TASK_OCCURRENCE",occurrenceId,null,event(created),requestId);
            if(handoff!=null)events.taskNotification(groupId,"HANDOFF_OPEN","handoff-open:"+handoff,occurrenceId,handoff,null,0L,
                    Map.of("schemaVersion",1,"reason","NO_CANDIDATE"),now);
            else events.taskNotification(groupId,"TASK_ASSIGNED","task-assigned:"+occurrenceId+":0",occurrenceId,null,assignee,0L,
                    Map.of("schemaVersion",1),now);
            occurrences.add(dto(created));
        }
        TaskDtos.CreateResponse result=new TaskDtos.CreateResponse(seriesId,0,request.rule(),occurrences,new TaskDtos.GenerationWindow(today,horizon));
        return new MutationResponse(201,DataResponse.of(result));
    }

    public IdempotentResult assign(UUID occurrenceId,UUID userId,TaskDtos.AssignmentRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T09:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),()->{
            Occurrence before=requireOccurrence(occurrenceId); requireMutablePending(before,request.expectedVersion(),true);
            GroupMember target=groups.findActiveMembership(before.groupId(),request.assigneeUserId()).orElseThrow(()->
                    new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","담당자는 같은 공동체의 ACTIVE 구성원이어야 합니다."));
            validateAssignable(target.userId(),before);
            if(repository.assign(before.id(),before.version(),target.userId())!=1)throw versionConflict(before.version());
            Instant now=clock.instant();repository.acceptOpenHandoff(before.id(),target.userId(),now);repository.cancelPendingNotifications(before.id());
            Occurrence after=requireOccurrence(before.id());events.audit(before.groupId(),userId,"TASK_ASSIGNED_MANUAL","TASK_OCCURRENCE",before.id(),event(before),event(after),requestId);
            events.taskNotification(before.groupId(),"TASK_ASSIGNED","task-assigned:"+before.id()+":"+after.version(),before.id(),null,target.userId(),after.version(),Map.of("schemaVersion",1),now);
            return new MutationResponse(200,DataResponse.of(new TaskDtos.OccurrenceResponse(dto(after))));});
    }

    public IdempotentResult complete(UUID occurrenceId,UUID userId,TaskDtos.CompleteRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T12:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),()->{
            Occurrence before=requireOccurrence(occurrenceId);requireMutablePending(before,request.expectedVersion(),false);
            if(groups.findActiveMembership(before.groupId(),request.performedByUserId()).isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","수행자는 ACTIVE 구성원이어야 합니다.");
            Instant now=clock.instant();if(repository.complete(before.id(),before.version(),userId,request.performedByUserId(),now)!=1)throw versionConflict(before.version());
            repository.closeOpenHandoff(before.id(),"TASK_COMPLETED",now);repository.cancelPendingNotifications(before.id());Occurrence after=requireOccurrence(before.id());
            events.audit(before.groupId(),userId,"TASK_COMPLETED","TASK_OCCURRENCE",before.id(),event(before),event(after),requestId);
            events.taskNotification(before.groupId(),"TASK_COMPLETED","task-completed:"+before.id()+":"+after.version(),before.id(),null,null,after.version(),Map.of("schemaVersion",1),now);
            return new MutationResponse(200,DataResponse.of(new TaskDtos.OccurrenceResponse(dto(after))));});
    }

    public IdempotentResult reopen(UUID occurrenceId,UUID userId,TaskDtos.ReopenRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T13:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),()->{
            Occurrence before=requireOccurrence(occurrenceId);if(before.version()!=request.expectedVersion())throw versionConflict(before.version());
            if(!"COMPLETED".equals(before.status()))throw invalidState("완료된 일정만 다시 열 수 있습니다.");
            boolean keep=before.assigneeUserId()!=null&&groups.findActiveMembership(before.groupId(),before.assigneeUserId()).isPresent()
                    &&assignments.canAssign(before.assigneeUserId(),before.startsAt(),before.endsAt(),before.id());
            if(repository.reopen(before.id(),before.version(),keep)!=1)throw versionConflict(before.version());
            Instant now=clock.instant();repository.cancelPendingNotifications(before.id());UUID handoff=null;if(!keep&&before.endsAt().isAfter(now))handoff=repository.openNoCandidate(before.groupId(),before.id());
            Occurrence after=requireOccurrence(before.id());events.audit(before.groupId(),userId,"TASK_REOPENED","TASK_OCCURRENCE",before.id(),event(before),event(after),requestId);
            if(handoff!=null)events.taskNotification(before.groupId(),"HANDOFF_OPEN","handoff-open:"+handoff,before.id(),handoff,null,after.version(),Map.of("schemaVersion",1,"reason","NO_CANDIDATE"),now);
            else events.taskNotification(before.groupId(),"TASK_REOPENED","task-reopened:"+before.id()+":"+after.version(),before.id(),null,after.assigneeUserId(),after.version(),Map.of("schemaVersion",1),now);
            return new MutationResponse(200,DataResponse.of(new TaskDtos.OccurrenceResponse(dto(after))));});
    }

    public TaskDtos.Page<TaskDtos.History> history(UUID occurrenceId,UUID userId,Integer requestedLimit,String cursor){
        Occurrence occurrence=requireOccurrence(occurrenceId);requireMember(occurrence.groupId(),userId);int limit=limit(requestedLimit);
        List<Audit> rows=repository.history(occurrence.groupId(),occurrenceId,limit+1,cursors.decode(cursor));boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        String next=more?cursors.encode(rows.getLast().createdAt(),rows.getLast().id()):null;
        return new TaskDtos.Page<>(rows.stream().map(this::historyDto).toList(),next,more);
    }

    private void validateCreate(TaskDtos.CreateRequest request){
        if(!GENERAL_KINDS.contains(request.kind())){
            if("MEDICATION".equals(request.kind()))throw validation("MEDICATION 일정은 처방 확인 단계에서만 생성할 수 있습니다.");
            throw validation("kind가 올바르지 않습니다.");}
        if(!"ONCE".equals(request.rule().recurrence())||!request.rule().firstDate().equals(request.rule().lastDate())||!request.rule().weekdays().isEmpty())
            throw validation("현재는 ONCE 일반 일정만 지원합니다.");
        if(request.medicationIds()!=null&&!request.medicationIds().isEmpty())throw validation("일반 일정에는 medicationIds를 지정할 수 없습니다.");
    }
    private void validateAssignable(UUID userId,Occurrence occurrence){
        if(!assignments.isAvailable(userId,occurrence.startsAt(),occurrence.endsAt()))
            throw new ApiException(HttpStatus.CONFLICT,"NOT_AVAILABLE","담당자의 가능 시간에 포함되지 않습니다.");
        if(assignments.hasConflict(userId,occurrence.startsAt(),occurrence.endsAt(),occurrence.id()))
            throw new ApiException(HttpStatus.CONFLICT,"TIME_CONFLICT","담당자의 다른 공동체 일정과 충돌합니다.");
    }
    private void requireMutablePending(Occurrence value,long expected,boolean rejectOverdue){if(value.version()!=expected)throw versionConflict(value.version());
        if(!"PENDING".equals(value.status()))throw invalidState("PENDING 일정만 변경할 수 있습니다.");
        if(rejectOverdue&&!value.endsAt().isAfter(clock.instant()))throw new ApiException(HttpStatus.CONFLICT,"TASK_OVERDUE","기한이 지난 일정입니다.");}
    private void authorizeOccurrence(UUID id,UUID userId){Occurrence value=requireOccurrence(id);requireMember(value.groupId(),userId);}
    private GroupMember requireMember(UUID groupId,UUID userId){return groups.findActiveMembership(groupId,userId).orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"NOT_MEMBER","공동체의 ACTIVE 구성원이 아닙니다."));}
    private Occurrence requireOccurrence(UUID id){return repository.findOccurrence(id).orElseThrow(this::notFound);}
    private int limit(Integer value){int result=value==null?20:value;if(result<1||result>100)throw validation("limit은 1~100이어야 합니다.");return result;}

    private TaskDtos.Task dto(Occurrence value){Handoff open=repository.findOpenHandoff(value.id()).orElse(null);return new TaskDtos.Task(value.id(),value.groupId(),value.seriesId(),value.seriesVersion(),value.revisionNo(),value.anchorDate(),value.kind(),value.title(),value.description(),atKst(value.startsAt()),atKst(value.endsAt()),atKst(value.endsAt()),value.status(),ref(value.assigneeUserId(),value.assigneeName()),value.assigneeUserId()==null?null:value.assignmentOrigin(),handoffDto(open),"PENDING".equals(value.status())&&!value.endsAt().isAfter(clock.instant()),value.override(),repository.occurrenceMedications(value.id()),value.completedAt()==null?null:new TaskDtos.Completion(ref(value.performedBy(),value.performedByName()),ref(value.completedBy(),value.completedByName()),atKst(value.completedAt())),value.canceledAt()==null?null:new TaskDtos.Cancellation(value.cancelReason(),atKst(value.canceledAt())),repository.sourceEncounterIds(value.seriesId(),value.id()),value.version());}
    private TaskDtos.Handoff handoffDto(Handoff value){return value==null?null:new TaskDtos.Handoff(value.id(),value.occurrenceId(),value.reason(),ref(value.previousId(),value.previousName()),ref(value.requestedBy(),value.requestedName()),value.status(),ref(value.acceptedBy(),value.acceptedName()),value.closedAt()==null?null:atKst(value.closedAt()),value.closeReason(),value.version());}
    private TaskDtos.UserRef ref(UUID id,String name){return id==null?null:new TaskDtos.UserRef(id,name);}
    private TaskDtos.Rule rule(Series value){return new TaskDtos.Rule(value.recurrence(),value.firstDate(),value.lastDate(),value.weekdays(),value.localTime(),value.durationMinutes());}
    private OffsetDateTime atKst(Instant value){return value.atZone(KST).toOffsetDateTime();}
    private Object event(Occurrence value){return Map.of("executionStatus",value.status(),"assigneeUserId",value.assigneeUserId()==null?"":value.assigneeUserId().toString(),"assignmentOrigin",value.assignmentOrigin()==null?"":value.assignmentOrigin(),"completedBy",value.completedBy()==null?"":value.completedBy().toString(),"performedBy",value.performedBy()==null?"":value.performedBy().toString(),"completedAt",value.completedAt()==null?"":value.completedAt().toString(),"version",value.version());}
    private TaskDtos.History historyDto(Audit value){JsonNode before=read(value.beforeJson()),after=read(value.afterJson());Set<String> fields=new LinkedHashSet<>(List.of("executionStatus","assigneeUserId","assignmentOrigin","performedBy","completedBy","completedAt","version"));List<TaskDtos.Change> changes=new ArrayList<>();for(String field:fields){JsonNode a=before==null?null:before.get(field),b=after==null?null:after.get(field);if(!java.util.Objects.equals(a,b))changes.add(new TaskDtos.Change(field,nodeValue(a),nodeValue(b)));}return new TaskDtos.History(value.id(),value.eventType(),ref(value.actorId(),value.actorName()),atKst(value.createdAt()),changes);}
    private JsonNode read(String json){try{return json==null?null:objectMapper.readTree(json);}catch(RuntimeException e){return null;}}
    private Object nodeValue(JsonNode node){if(node==null||node.isNull())return null;if(node.isNumber())return node.numberValue();if(node.isBoolean())return node.booleanValue();return node.asText();}
    private ApiException validation(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private ApiException versionConflict(long current){return new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","일정이 변경되었습니다.",Map.of("currentVersion",current));}
    private ApiException invalidState(String message){return new ApiException(HttpStatus.CONFLICT,"INVALID_STATE",message);}
    private ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","일정을 찾을 수 없습니다.");}
}
