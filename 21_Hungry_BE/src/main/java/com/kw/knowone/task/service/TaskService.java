package com.kw.knowone.task.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.idempotency.MutationResponse;
import com.kw.knowone.common.crypto.FingerprintService;
import com.kw.knowone.common.preview.PreviewTokenService;
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
    private final TaskGenerationService generator;
    private final PreviewTokenService previews;
    private final FingerprintService fingerprints;
    private final HandoffExpiryService handoffExpiry;

    public TaskService(TaskRepository repository, GroupRepository groups, GroupEventRepository events,
            ScheduleAssignmentService assignments, ScheduleMutationService mutations, CursorService cursors,
            ObjectMapper objectMapper, Clock clock,TaskGenerationService generator,
            PreviewTokenService previews,FingerprintService fingerprints,HandoffExpiryService handoffExpiry) {
        this.repository=repository; this.groups=groups; this.events=events; this.assignments=assignments;
        this.mutations=mutations; this.cursors=cursors; this.objectMapper=objectMapper; this.clock=clock;
        this.generator=generator;this.previews=previews;this.fingerprints=fingerprints;
        this.handoffExpiry=handoffExpiry;
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

    public com.kw.knowone.group.dto.GroupDtos.Home home(UUID groupId,UUID userId,LocalDate date,Integer requestedLimit){
        requireMember(groupId,userId);int limit=limit(requestedLimit);LocalDate target=date==null?LocalDate.now(clock):date;
        Instant now=clock.instant(),from=target.atStartOfDay(KST).toInstant(),to=target.plusDays(1).atStartOfDay(KST).toInstant();
        List<Occurrence> mine=repository.homeToday(groupId,userId,from,to,limit+1);
        List<Occurrence> unassigned=repository.homeUnassigned(groupId,now,limit+1);
        List<Occurrence> overdue=repository.homeOverdue(groupId,now,limit+1);
        return new com.kw.knowone.group.dto.GroupDtos.Home(groupId,target,homeList(mine,limit),homeList(unassigned,limit),
                homeList(overdue,limit),repository.reviewEncounterCount(groupId));
    }
    public TaskDtos.Page<TaskDtos.Task> linkedToEncounter(UUID encounterId,UUID userId,int requestedLimit){UUID groupId=repository.findEncounterGroup(encounterId).orElseThrow(this::notFound);requireMember(groupId,userId);int limit=Math.min(Math.max(requestedLimit,1),100);List<Occurrence> rows=repository.linkedToEncounter(encounterId,limit+1);boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);return new TaskDtos.Page<>(rows.stream().map(this::dto).toList(),null,more);}

    public TaskDtos.SeriesEditPreview editPreview(UUID occurrenceId,UUID userId,TaskDtos.SeriesEditPreviewRequest request){
        Occurrence selected=requireOccurrence(occurrenceId);requireMember(selected.groupId(),userId);
        Series series=repository.findSeries(selected.seriesId()).orElseThrow(this::notFound);
        requireVersions(selected,request.expectedVersion(),series,request.expectedSeriesVersion());
        if("ONCE".equals(series.recurrence()))throw invalidState("반복 일정만 일괄 수정할 수 있습니다.");
        Instant cutoff=clock.instant();EditPlan plan=editPlan(series,cutoff,request.patch());
        String payload=fingerprints.of(editPayload(request.expectedVersion(),request.expectedSeriesVersion(),request.patch()));
        String state=editState(series,selected,plan,cutoff);String token=previews.issue(userId,"T06:"+occurrenceId,payload,state,cutoff);
        PreviewTokenService.Claims claims=previews.verify(token,userId,"T06:"+occurrenceId,payload);
        return new TaskDtos.SeriesEditPreview(token,atKst(claims.expiresAt()),series.id(),series.version(),atKst(cutoff),
                new TaskDtos.PreviewCounts(plan.updated().size(),plan.canceled().size(),plan.addedDates().size(),plan.overwritten()),
                plan.affectedIds(),plan.affectsUnmaterialized(),rule(plan.next()),plan.releaseCount());
    }

    public IdempotentResult update(UUID occurrenceId,UUID userId,TaskDtos.UpdateRequest request,String key,UUID requestId){
        if("OCCURRENCE".equals(request.scope()))handoffExpiry.expireOccurrenceIfOverdue(occurrenceId);
        return mutations.execute(userId,"T06:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),
                ()->"OCCURRENCE".equals(request.scope())?updateOne(occurrenceId,userId,request,requestId):
                    updateSeries(occurrenceId,userId,request,requestId));
    }

    private MutationResponse updateOne(UUID occurrenceId,UUID userId,TaskDtos.UpdateRequest request,UUID requestId){
        if(request.expectedSeriesVersion()!=null||request.previewToken()!=null)throw validation("단건 수정에는 seriesVersion과 previewToken을 보내지 않습니다.");
        Occurrence before=requireOccurrence(occurrenceId);requireMutablePending(before,request.expectedVersion(),false);
        JsonNode patch=requireObjectPatch(request.patch());
        String title=patch.has("title")?requiredTitle(patch.get("title")):before.title();
        String description=patch.has("description")?(patch.get("description").isNull()?null:patch.get("description").asText()):before.description();
        boolean hasStart=patch.has("startsAt"),hasEnd=patch.has("endsAt");
        if(hasStart!=hasEnd)throw validation("startsAt과 endsAt은 함께 보내야 합니다.");
        if(!patch.has("title")&&!patch.has("description")&&!hasStart)throw validation("수정할 필드가 없습니다.");
        Instant starts=hasStart?parseOffset(patch.get("startsAt"),"startsAt"):before.startsAt();
        Instant ends=hasEnd?parseOffset(patch.get("endsAt"),"endsAt"):before.endsAt();validateDuration(starts,ends);
        if(!ends.isAfter(clock.instant()))throw new ApiException(HttpStatus.CONFLICT,"INVALID_STATE","경과 일정은 미래로 이동해야 합니다.");
        boolean keep=before.assigneeUserId()==null||assignments.canAssign(before.assigneeUserId(),starts,ends,before.id());
        if(repository.updateOccurrenceOverride(before.id(),before.version(),title,description,starts,ends,keep)!=1)throw versionConflict(before.version());
        Instant now=clock.instant();repository.cancelPendingNotifications(before.id());repository.cancelPendingDeliveries(before.id());
        UUID handoff=null;if(!keep&&before.assigneeUserId()!=null)handoff=repository.openHandoff(before.groupId(),before.id(),"AVAILABILITY",before.assigneeUserId(),userId);
        if(before.assigneeUserId()==null&&repository.findOpenHandoff(before.id()).isEmpty())handoff=repository.openNoCandidate(before.groupId(),before.id());
        Occurrence after=requireOccurrence(before.id());events.audit(before.groupId(),userId,"TASK_OCCURRENCE_UPDATED","TASK_OCCURRENCE",before.id(),event(before),event(after),requestId);
        if(handoff!=null)events.taskNotification(before.groupId(),"HANDOFF_OPEN","handoff-open:"+handoff,before.id(),handoff,null,after.version(),Map.of("schemaVersion",1,"reason",!keep?"AVAILABILITY":"NO_CANDIDATE"),now);
        return new MutationResponse(200,DataResponse.of(new TaskDtos.OccurrenceResponse(dto(after))));
    }

    private MutationResponse updateSeries(UUID occurrenceId,UUID userId,TaskDtos.UpdateRequest request,UUID requestId){
        if(!"SERIES_ALL_PENDING".equals(request.scope())||request.expectedSeriesVersion()==null||request.previewToken()==null)throw validation("일괄 수정 요청이 올바르지 않습니다.");
        Occurrence selected=requireOccurrence(occurrenceId);Series series=repository.findSeries(selected.seriesId()).orElseThrow(this::notFound);
        requireVersions(selected,request.expectedVersion(),series,request.expectedSeriesVersion());
        String payload=fingerprints.of(editPayload(request.expectedVersion(),request.expectedSeriesVersion(),request.patch()));
        PreviewTokenService.Claims claims=previews.verify(request.previewToken(),userId,"T06:"+occurrenceId,payload);
        EditPlan plan=editPlan(series,claims.issuedAt(),request.patch());
        if(!claims.stateFingerprint().equals(editState(series,selected,plan,claims.issuedAt())))throw previewStale();
        Instant now=clock.instant();int revision=series.currentRevisionNo()+1;
        repository.insertRevision(series.id(),series.groupId(),revision,plan.next().title(),plan.next().description(),plan.next().recurrence(),
                plan.next().firstDate(),plan.next().lastDate(),plan.next().weekdays(),plan.next().localTime(),plan.next().durationMinutes(),userId,claims.issuedAt());
        if("MEDICATION".equals(series.kind()))repository.copySeriesMedications(series.groupId(),series.id(),series.currentRevisionNo(),revision);
        if(repository.advanceSeries(series.id(),series.version(),revision)!=1)throw versionConflict(series.version());
        List<UUID> updated=new ArrayList<>(),canceled=new ArrayList<>(),released=new ArrayList<>();
        for(Occurrence before:plan.updated()){
            Instant starts=at(before.anchorDate(),plan.next().localTime()),ends=starts.plus(Duration.ofMinutes(plan.next().durationMinutes()));
            if(starts.isBefore(now))throw new ApiException(HttpStatus.CONFLICT,"PREVIEW_STALE","새 시작 시각이 현재보다 이전입니다.");
            boolean keep=before.assigneeUserId()==null||assignments.canAssign(before.assigneeUserId(),starts,ends,before.id());
            if(repository.applySeriesRevision(before.id(),before.version(),revision,plan.next().title(),plan.next().description(),starts,ends,keep)!=1)throw versionConflict(before.version());
            repository.cancelPendingNotifications(before.id());repository.cancelPendingDeliveries(before.id());
            if(!keep&&before.assigneeUserId()!=null){UUID h=repository.openHandoff(before.groupId(),before.id(),"AVAILABILITY",before.assigneeUserId(),userId);released.add(before.id());
                if(h!=null)events.taskNotification(before.groupId(),"HANDOFF_OPEN","handoff-open:"+h,before.id(),h,null,before.version()+1,Map.of("schemaVersion",1,"reason","AVAILABILITY"),now);}
            events.audit(before.groupId(),userId,"TASK_SERIES_OCCURRENCE_UPDATED","TASK_OCCURRENCE",before.id(),event(before),event(requireOccurrence(before.id())),requestId);updated.add(before.id());
        }
        for(Occurrence before:plan.canceled()){cancel(before,userId,"RULE_CHANGED",requestId,now);canceled.add(before.id());}
        List<Occurrence> revivedValues=new ArrayList<>();for(Occurrence before:plan.revived()){
            Instant starts=at(before.anchorDate(),plan.next().localTime()),ends=starts.plus(Duration.ofMinutes(plan.next().durationMinutes()));
            if(starts.isBefore(now))throw previewStale();
            if(repository.reviveRuleChanged(before.id(),before.version(),revision,plan.next().title(),plan.next().description(),starts,ends)!=1)throw versionConflict(before.version());
            revivedValues.add(requireOccurrence(before.id()));updated.add(before.id());
        }
        assignments.assignNew(revivedValues);for(Occurrence revived:revivedValues){Occurrence assigned=requireOccurrence(revived.id());UUID h=null;if(assigned.assigneeUserId()==null)h=repository.openNoCandidate(assigned.groupId(),assigned.id());
            if(h!=null)events.taskNotification(assigned.groupId(),"HANDOFF_OPEN","handoff-open:"+h,assigned.id(),h,null,assigned.version(),Map.of("schemaVersion",1,"reason","NO_CANDIDATE"),now);
            else events.taskNotification(assigned.groupId(),"TASK_ASSIGNED","task-assigned:"+assigned.id()+":"+assigned.version(),assigned.id(),null,assigned.assigneeUserId(),assigned.version(),Map.of("schemaVersion",1),now);}
        List<UUID> created=generator.generateSeriesLocked(series.id(),requestId);
        Series after=repository.findSeries(series.id()).orElseThrow();
        events.audit(series.groupId(),userId,"TASK_SERIES_UPDATED","TASK_SERIES",series.id(),Map.of("version",series.version(),"revisionNo",series.currentRevisionNo()),Map.of("version",after.version(),"revisionNo",after.currentRevisionNo()),requestId);
        return new MutationResponse(200,DataResponse.of(new TaskDtos.SeriesUpdateResponse(series.id(),after.version(),updated,canceled,created,released)));
    }

    public TaskDtos.DeletionPreview deletionPreview(UUID occurrenceId,UUID userId,TaskDtos.DeletionPreviewRequest request){
        Occurrence selected=requireOccurrence(occurrenceId);requireMember(selected.groupId(),userId);Series series=repository.findSeries(selected.seriesId()).orElseThrow(this::notFound);
        validateDeleteScope(request.scope(),series);validateDeleteSeriesVersion(request.scope(),request.expectedSeriesVersion());requireVersions(selected,request.expectedVersion(),series,request.expectedSeriesVersion());
        DeletePlan plan=deletePlan(selected,series,request.scope());String payload=fingerprints.of(deletePayload(request.scope(),request.expectedVersion(),request.expectedSeriesVersion()));
        String state=deleteState(series,selected,plan);String token=previews.issue(userId,"T08:"+occurrenceId,payload,state);
        PreviewTokenService.Claims claims=previews.verify(token,userId,"T08:"+occurrenceId,payload);
        return new TaskDtos.DeletionPreview(token,atKst(claims.expiresAt()),request.scope(),selected.anchorDate(),
                plan.cancel().stream().map(Occurrence::id).toList(),plan.preservedCompleted(),plan.movedOverrides(),plan.affectsUnmaterialized());
    }

    public IdempotentResult delete(UUID occurrenceId,UUID userId,TaskDtos.DeleteRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T08:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),()->{
            Occurrence selected=requireOccurrence(occurrenceId);Series series=repository.findSeries(selected.seriesId()).orElseThrow(this::notFound);
            validateDeleteScope(request.scope(),series);validateDeleteSeriesVersion(request.scope(),request.expectedSeriesVersion());requireVersions(selected,request.expectedVersion(),series,request.expectedSeriesVersion());
            String payload=fingerprints.of(deletePayload(request.scope(),request.expectedVersion(),request.expectedSeriesVersion()));
            PreviewTokenService.Claims claims=previews.verify(request.previewToken(),userId,"T08:"+occurrenceId,payload);
            DeletePlan plan=deletePlan(selected,series,request.scope());if(!claims.stateFingerprint().equals(deleteState(series,selected,plan)))throw previewStale();
            Instant now=clock.instant();List<UUID> ids=new ArrayList<>();for(Occurrence value:plan.cancel()){cancel(value,userId,"OCCURRENCE".equals(request.scope())?"USER_ONE":"USER_FUTURE",requestId,now);ids.add(value.id());}
            LocalDate stop=series.stopFromDate();long seriesVersion=series.version();if("SERIES_FROM_SELECTED".equals(request.scope())){
                if(repository.stopSeries(series.id(),series.version(),selected.anchorDate())!=1)throw versionConflict(series.version());
                Series after=repository.findSeries(series.id()).orElseThrow();stop=after.stopFromDate();seriesVersion=after.version();}
            return new MutationResponse(200,DataResponse.of(new TaskDtos.DeleteResponse(ids,stop,seriesVersion)));});
    }

    public IdempotentResult create(UUID groupId,UUID userId,TaskDtos.CreateRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T03:"+groupId,key,request,()->requireMember(groupId,userId),()->doCreate(groupId,userId,request,key,requestId));
    }
    private MutationResponse doCreate(UUID groupId,UUID userId,TaskDtos.CreateRequest request,String key,UUID requestId){
        validateCreate(request); Instant now=clock.instant(); LocalDate today=LocalDate.now(clock); LocalDate horizon=today.plusDays(14);
        List<UUID> medicationIds=List.of();if("MEDICATION".equals(request.kind())){medicationIds=request.medicationIds().stream().distinct().toList();
            if(medicationIds.size()!=request.medicationIds().size()||repository.medicationsByIds(groupId,medicationIds).size()!=medicationIds.size())throw validation("같은 공동체의 확정 처방만 사용할 수 있습니다.");
            var existing=repository.findMedicationSeries(groupId,request.rule().recurrence(),request.rule().weekdays(),request.rule().localTime(),request.rule().durationMinutes());if(existing.isPresent()){Series series=existing.get();List<UUID> linked=repository.seriesMedications(series.id(),series.currentRevisionNo()).stream().map(TaskDtos.Medication::id).toList();
                if(linked.containsAll(medicationIds))throw new ApiException(HttpStatus.CONFLICT,"DUPLICATE_MEDICATION_CONFLICT","동일 복약 계획에 이미 연결된 처방입니다.");int revision=series.currentRevisionNo()+1;LocalDate first=series.firstDate().isBefore(request.rule().firstDate())?series.firstDate():request.rule().firstDate();LocalDate last=series.lastDate().isAfter(request.rule().lastDate())?series.lastDate():request.rule().lastDate();
                repository.insertRevision(series.id(),groupId,revision,series.title(),series.description(),series.recurrence(),first,last,series.weekdays(),series.localTime(),series.durationMinutes(),userId,now);repository.copySeriesMedications(groupId,series.id(),series.currentRevisionNo(),revision);for(UUID id:medicationIds)repository.linkSeriesMedication(groupId,series.id(),revision,id);
                if(repository.advanceSeries(series.id(),series.version(),revision)!=1)throw versionConflict(series.version());repository.refreshFutureMedicationSnapshots(groupId,series.id(),revision,now);generator.generateSeriesLocked(series.id(),requestId);Series after=repository.findSeries(series.id()).orElseThrow();List<TaskDtos.Task> occurrences=repository.pendingOccurrenceIdsForMedications(medicationIds,now).stream().map(this::requireOccurrence).map(this::dto).toList();
                return new MutationResponse(200,DataResponse.of(new TaskDtos.CreateResponse(series.id(),after.version(),rule(after),occurrences,new TaskDtos.GenerationWindow(today,horizon))));}}
        LocalDate last=request.rule().lastDate();
        if(last!=null&&!last.atTime(request.rule().localTime()).atZone(KST).toInstant().plus(Duration.ofMinutes(request.rule().durationMinutes())).isAfter(now))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION_ERROR","전체가 과거인 일정은 생성할 수 없습니다.");
        UUID seriesId=repository.insertSeries(groupId,userId,request.kind(),"manual:"+userId+":"+key);
        repository.insertRevision(seriesId,groupId,1,request.title().trim(),request.description(),request.rule().recurrence(),
                request.rule().firstDate(),request.rule().lastDate(),request.rule().weekdays(),request.rule().localTime(),
                request.rule().durationMinutes(),userId,now);
        if("MEDICATION".equals(request.kind()))for(UUID medicationId:medicationIds)repository.linkSeriesMedication(groupId,seriesId,1,medicationId);
        events.audit(groupId,userId,"TASK_SERIES_CREATED","TASK_SERIES",seriesId,null,
                Map.of("kind",request.kind(),"recurrence","ONCE","version",0),requestId);
        List<TaskDtos.Task> occurrences=generator.generateSeriesLocked(seriesId,requestId).stream()
                .map(this::requireOccurrence).map(this::dto).toList();
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

    public IdempotentResult requestHandoff(UUID occurrenceId,UUID userId,TaskDtos.HandoffRequest request,String key,UUID requestId){
        return mutations.execute(userId,"T10:"+occurrenceId,key,request,()->authorizeOccurrence(occurrenceId,userId),()->{
            Occurrence before=requireOccurrence(occurrenceId);requireMutablePending(before,request.expectedVersion(),true);
            if(before.assigneeUserId()==null)throw invalidState("담당자가 있는 일정만 인계를 요청할 수 있습니다.");
            if(repository.findOpenHandoff(before.id()).isPresent())throw invalidState("이미 열린 인계 요청이 있습니다.");
            if(repository.releaseAssignee(before.id(),before.version())!=1)throw versionConflict(before.version());
            Instant now=clock.instant();UUID id=repository.openHandoff(before.groupId(),before.id(),"USER_REQUEST",before.assigneeUserId(),userId);
            if(id==null)throw invalidState("이미 열린 인계 요청이 있습니다.");
            repository.cancelPendingNotifications(before.id());repository.cancelPendingDeliveries(before.id());
            Occurrence after=requireOccurrence(before.id());Handoff handoff=repository.findHandoff(id).orElseThrow();
            events.audit(before.groupId(),userId,"HANDOFF_REQUESTED","TASK_OCCURRENCE",before.id(),event(before),event(after),requestId);
            events.taskNotification(before.groupId(),"HANDOFF_OPEN","handoff-open:"+id,before.id(),id,null,after.version(),Map.of("schemaVersion",1,"reason","USER_REQUEST"),now);
            return new MutationResponse(201,DataResponse.of(new TaskDtos.HandoffResponse(dto(after),handoffDto(handoff))));});
    }

    public IdempotentResult acceptHandoff(UUID handoffId,UUID userId,TaskDtos.HandoffAcceptRequest request,String key,UUID requestId){
        handoffExpiry.expireIfOverdue(handoffId);
        return mutations.execute(userId,"T11:"+handoffId,key,request,()->authorizeHandoff(handoffId,userId),()->{
            Handoff before=repository.findHandoff(handoffId).orElseThrow(this::notFound);
            Occurrence occurrence=requireOccurrence(before.occurrenceId());
            if(!"OPEN".equals(before.status())){
                if("EXPIRED".equals(before.status()))throw new ApiException(HttpStatus.CONFLICT,"TASK_OVERDUE","기한이 지난 일정입니다.");
                throw new ApiException(HttpStatus.CONFLICT,"ALREADY_ASSIGNED","이미 종료된 인계 요청입니다.");}
            if(before.version()!=request.expectedVersion()||occurrence.version()!=request.expectedOccurrenceVersion())throw versionConflict(occurrence.version());
            if(!"PENDING".equals(occurrence.status())||occurrence.assigneeUserId()!=null)throw new ApiException(HttpStatus.CONFLICT,"ALREADY_ASSIGNED","이미 담당자가 정해졌습니다.");
            if(!occurrence.endsAt().isAfter(clock.instant()))throw new ApiException(HttpStatus.CONFLICT,"TASK_OVERDUE","기한이 지난 일정입니다.");
            GroupMember member=groups.findActiveMembership(occurrence.groupId(),userId).orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"NOT_MEMBER","ACTIVE 구성원이 아닙니다."));
            if(!assignments.isAvailable(member.userId(),occurrence.startsAt(),occurrence.endsAt()))throw new ApiException(HttpStatus.CONFLICT,"NOT_AVAILABLE","가능 시간에 포함되지 않습니다.");
            if(assignments.hasConflict(member.userId(),occurrence.startsAt(),occurrence.endsAt(),occurrence.id()))throw new ApiException(HttpStatus.CONFLICT,"TIME_CONFLICT","다른 일정과 충돌합니다.");
            Instant now=clock.instant();if(repository.acceptHandoff(before.id(),before.version(),occurrence.id(),occurrence.version(),userId,now)!=1)throw new ApiException(HttpStatus.CONFLICT,"ALREADY_ASSIGNED","이미 담당자가 정해졌습니다.");
            repository.cancelPendingNotifications(occurrence.id());repository.cancelPendingDeliveries(occurrence.id());
            Handoff afterHandoff=repository.findHandoff(before.id()).orElseThrow();Occurrence after=requireOccurrence(occurrence.id());
            events.audit(occurrence.groupId(),userId,"HANDOFF_ACCEPTED","TASK_OCCURRENCE",occurrence.id(),event(occurrence),event(after),requestId);
            events.taskNotification(occurrence.groupId(),"HANDOFF_ACCEPTED","handoff-accepted:"+before.id()+":"+afterHandoff.version(),occurrence.id(),before.id(),null,after.version(),Map.of("schemaVersion",1,"acceptedBy",userId),now);
            return new MutationResponse(200,DataResponse.of(new TaskDtos.HandoffResponse(dto(after),handoffDto(afterHandoff))));});
    }

    public TaskDtos.Page<TaskDtos.HandoffItem> handoffs(UUID groupId,UUID userId,String status,Integer requestedLimit,String cursor){
        requireMember(groupId,userId);if("OPEN".equals(status))handoffExpiry.expireGroup(groupId);if(!Set.of("OPEN","ACCEPTED","CLOSED","EXPIRED").contains(status))throw validation("status가 올바르지 않습니다.");
        int limit=limit(requestedLimit);List<Handoff> rows=repository.listHandoffs(groupId,status,limit+1,cursors.decode(cursor));boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        String next=more?cursors.encode(rows.getLast().createdAt(),rows.getLast().id()):null;
        return new TaskDtos.Page<>(rows.stream().map(value->new TaskDtos.HandoffItem(value.id(),value.occurrenceId(),value.reason(),ref(value.previousId(),value.previousName()),ref(value.requestedBy(),value.requestedName()),value.status(),ref(value.acceptedBy(),value.acceptedName()),value.closedAt()==null?null:atKst(value.closedAt()),value.closeReason(),value.version(),dto(requireOccurrence(value.occurrenceId())))).toList(),next,more);
    }

    public TaskDtos.Page<TaskDtos.History> history(UUID occurrenceId,UUID userId,Integer requestedLimit,String cursor){
        Occurrence occurrence=requireOccurrence(occurrenceId);requireMember(occurrence.groupId(),userId);int limit=limit(requestedLimit);
        List<Audit> rows=repository.history(occurrence.groupId(),occurrenceId,limit+1,cursors.decode(cursor));boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        String next=more?cursors.encode(rows.getLast().createdAt(),rows.getLast().id()):null;
        return new TaskDtos.Page<>(rows.stream().map(this::historyDto).toList(),next,more);
    }

    private com.kw.knowone.group.dto.GroupDtos.HomeTaskList homeList(List<Occurrence> rows,int limit){
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        return new com.kw.knowone.group.dto.GroupDtos.HomeTaskList(rows.stream().map(this::dto).toList(),more);
    }

    private EditPlan editPlan(Series series,Instant cutoff,JsonNode patch){
        ScheduleRule next=patchedRule(series,patch);List<Occurrence> all=repository.findSeriesOccurrences(series.id());
        List<Occurrence> updated=new ArrayList<>(),canceled=new ArrayList<>(),revived=new ArrayList<>();
        int overwritten=0,releases=0;Set<LocalDate> existing=new HashSet<>();
        LocalDate today=LocalDate.now(clock),horizon=today.plusDays(14);
        for(Occurrence value:all){existing.add(value.anchorDate());boolean included=occurs(next,value.anchorDate())&&beforeStop(series,value.anchorDate());
            if("PENDING".equals(value.status())&&!value.startsAt().isBefore(cutoff)){
                if(included){updated.add(value);if(value.override())overwritten++;
                    if(value.assigneeUserId()!=null){Instant starts=at(value.anchorDate(),next.localTime()),ends=starts.plus(Duration.ofMinutes(next.durationMinutes()));if(!assignments.canAssign(value.assigneeUserId(),starts,ends,value.id()))releases++;}}
                else canceled.add(value);
            }else if("CANCELED".equals(value.status())&&"RULE_CHANGED".equals(value.cancelReason())&&included
                    &&!value.anchorDate().isBefore(today)&&value.anchorDate().isBefore(horizon)
                    &&!at(value.anchorDate(),next.localTime()).isBefore(cutoff))revived.add(value);
        }
        List<LocalDate> added=new ArrayList<>();LocalDate from=next.firstDate().isAfter(today)?next.firstDate():today;
        LocalDate until=next.lastDate()==null?horizon:(next.lastDate().plusDays(1).isBefore(horizon)?next.lastDate().plusDays(1):horizon);
        for(LocalDate date=from;date.isBefore(until);date=date.plusDays(1))if(beforeStop(series,date)&&occurs(next,date)&&!existing.contains(date)&&!at(date,next.localTime()).isBefore(cutoff))added.add(date);
        List<UUID> affected=new ArrayList<>();updated.forEach(v->affected.add(v.id()));canceled.forEach(v->affected.add(v.id()));revived.forEach(v->affected.add(v.id()));
        boolean future=!"ONCE".equals(next.recurrence())&&(next.lastDate()==null||!next.lastDate().isBefore(horizon));
        return new EditPlan(next,updated,canceled,revived,added,affected,overwritten,releases,future);
    }

    private ScheduleRule patchedRule(Series series,JsonNode patch){
        patch=requireObjectPatch(patch);Set<String> allowed=Set.of("title","description","localTime","durationMinutes","recurrence","firstDate","lastDate","weekdays");
        boolean any=allowed.stream().anyMatch(patch::has);if(!any)throw validation("수정할 규칙 필드가 없습니다.");
        if(patch.has("assignee")||patch.has("assigneeUserId")||patch.has("medicationIds"))throw validation("담당자와 약 정보는 일괄 수정할 수 없습니다.");
        String title=patch.has("title")?requiredTitle(patch.get("title")):series.title();
        String description=patch.has("description")?(patch.get("description").isNull()?null:patch.get("description").asText()):series.description();
        String recurrence=patch.has("recurrence")?patch.get("recurrence").asText():series.recurrence();
        LocalDate first=patch.has("firstDate")?parseDate(patch.get("firstDate"),"firstDate"):series.firstDate();
        LocalDate last=patch.has("lastDate")?(patch.get("lastDate").isNull()?null:parseDate(patch.get("lastDate"),"lastDate")):series.lastDate();
        LocalTime time=patch.has("localTime")?parseTime(patch.get("localTime"),"localTime"):series.localTime();
        int duration=patch.has("durationMinutes")?patch.get("durationMinutes").asInt():series.durationMinutes();
        List<Integer> weekdays=series.weekdays();if(patch.has("weekdays")){if(!patch.get("weekdays").isArray())throw validation("weekdays는 배열이어야 합니다.");List<Integer> values=new ArrayList<>();for(JsonNode value:patch.get("weekdays"))values.add(value.asInt());weekdays=values;}
        ScheduleRule value=new ScheduleRule(title,description,recurrence,first,last,weekdays,time,duration);validateRule(rule(value));return value;
    }

    private DeletePlan deletePlan(Occurrence selected,Series series,String scope){
        List<Occurrence> all=repository.findSeriesOccurrences(series.id());List<Occurrence> cancel;
        int completed=0,moved=0;
        if("OCCURRENCE".equals(scope)){
            if(!"PENDING".equals(selected.status()))throw invalidState("PENDING 일정만 삭제할 수 있습니다.");cancel=List.of(selected);
        }else{
            cancel=all.stream().filter(v->!v.anchorDate().isBefore(selected.anchorDate())&&"PENDING".equals(v.status())).toList();
            completed=(int)all.stream().filter(v->!v.anchorDate().isBefore(selected.anchorDate())&&"COMPLETED".equals(v.status())).count();
            moved=(int)cancel.stream().filter(Occurrence::override).count();
        }
        return new DeletePlan(cancel,completed,moved,"SERIES_FROM_SELECTED".equals(scope)&&!"ONCE".equals(series.recurrence()));
    }

    private void cancel(Occurrence before,UUID userId,String reason,UUID requestId,Instant now){
        if(repository.cancelOccurrence(before.id(),before.version(),reason,now)!=1)throw versionConflict(before.version());
        repository.closeOpenHandoff(before.id(),"TASK_CANCELED",now);repository.cancelPendingNotifications(before.id());repository.cancelPendingDeliveries(before.id());
        events.audit(before.groupId(),userId,"TASK_CANCELED","TASK_OCCURRENCE",before.id(),event(before),event(requireOccurrence(before.id())),requestId);
    }

    private String editState(Series series,Occurrence selected,EditPlan plan,Instant cutoff){return fingerprints.of(Map.of(
            "seriesVersion",series.version(),"selectedVersion",selected.version(),"cutoff",cutoff.toString(),
            "occurrences",plan.affectedIds().stream().map(id->{Occurrence v=requireOccurrence(id);return id+":"+v.version()+":"+v.status()+":"+v.startsAt();}).toList(),
            "addedDates",plan.addedDates().stream().map(LocalDate::toString).toList()));}
    private String deleteState(Series series,Occurrence selected,DeletePlan plan){return fingerprints.of(Map.of(
            "seriesVersion",series.version(),"selectedVersion",selected.version(),"occurrences",plan.cancel().stream().map(v->v.id()+":"+v.version()+":"+v.status()).toList(),"completed",plan.preservedCompleted()));}
    private Map<String,Object> editPayload(long occurrenceVersion,long seriesVersion,JsonNode patch){return Map.of("occurrenceVersion",occurrenceVersion,"seriesVersion",seriesVersion,"patch",patch);}
    private Map<String,Object> deletePayload(String scope,long occurrenceVersion,Long seriesVersion){Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("scope",scope);result.put("occurrenceVersion",occurrenceVersion);result.put("seriesVersion",seriesVersion);return result;}

    private void validateDeleteScope(String scope,Series series){
        if(!Set.of("OCCURRENCE","SERIES_FROM_SELECTED").contains(scope))throw validation("scope가 올바르지 않습니다.");
        if("SERIES_FROM_SELECTED".equals(scope)&&"ONCE".equals(series.recurrence()))throw validation("단발 일정은 OCCURRENCE만 허용합니다.");
    }
    private void validateDeleteSeriesVersion(String scope,Long version){
        if("OCCURRENCE".equals(scope)&&version!=null)throw validation("단건 삭제에는 expectedSeriesVersion을 보내지 않습니다.");
        if("SERIES_FROM_SELECTED".equals(scope)&&version==null)throw validation("앞으로 삭제에는 expectedSeriesVersion이 필요합니다.");
    }
    private void requireVersions(Occurrence occurrence,long expectedOccurrence,Series series,Long expectedSeries){
        if(occurrence.version()!=expectedOccurrence)throw versionConflict(occurrence.version());
        if(expectedSeries!=null&&series.version()!=expectedSeries)throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","시리즈가 변경되었습니다.",Map.of("currentSeriesVersion",series.version()));
    }
    private JsonNode requireObjectPatch(JsonNode patch){if(patch==null||!patch.isObject())throw validation("patch는 객체여야 합니다.");return patch;}
    private String requiredTitle(JsonNode value){String title=value==null||value.isNull()?"":value.asText().trim();if(title.isEmpty()||title.length()>150)throw validation("title은 1~150자여야 합니다.");return title;}
    private Instant parseOffset(JsonNode value,String field){try{return OffsetDateTime.parse(value.asText()).toInstant();}catch(RuntimeException e){throw validation(field+" 형식이 올바르지 않습니다.");}}
    private LocalDate parseDate(JsonNode value,String field){try{return LocalDate.parse(value.asText());}catch(RuntimeException e){throw validation(field+" 형식이 올바르지 않습니다.");}}
    private LocalTime parseTime(JsonNode value,String field){try{return LocalTime.parse(value.asText());}catch(RuntimeException e){throw validation(field+" 형식이 올바르지 않습니다.");}}
    private void validateDuration(Instant starts,Instant ends){long minutes=Duration.between(starts,ends).toMinutes();if(!starts.isBefore(ends)||minutes<1||Duration.between(starts,ends).compareTo(Duration.ofMinutes(1440))>0)throw validation("일정 기간은 1~1440분이어야 합니다.");}
    private Instant at(LocalDate date,LocalTime time){return date.atTime(time).atZone(KST).toInstant();}
    private boolean beforeStop(Series series,LocalDate date){return series.stopFromDate()==null||date.isBefore(series.stopFromDate());}
    private boolean occurs(ScheduleRule rule,LocalDate date){if(date.isBefore(rule.firstDate())||rule.lastDate()!=null&&date.isAfter(rule.lastDate()))return false;return switch(rule.recurrence()){case "ONCE"->date.equals(rule.firstDate());case "DAILY"->true;case "WEEKLY"->rule.weekdays().contains(date.getDayOfWeek().getValue());default->false;};}
    private TaskDtos.Rule rule(ScheduleRule value){return new TaskDtos.Rule(value.recurrence(),value.firstDate(),value.lastDate(),value.weekdays(),value.localTime(),value.durationMinutes());}
    private void authorizeHandoff(UUID id,UUID userId){Handoff handoff=repository.findHandoff(id).orElseThrow(this::notFound);Occurrence occurrence=requireOccurrence(handoff.occurrenceId());requireMember(occurrence.groupId(),userId);}
    private ApiException previewStale(){return new ApiException(HttpStatus.CONFLICT,"PREVIEW_STALE","미리보기 이후 일정 상태가 변경되었습니다.");}

    private record ScheduleRule(String title,String description,String recurrence,LocalDate firstDate,LocalDate lastDate,List<Integer> weekdays,LocalTime localTime,int durationMinutes){}
    private record EditPlan(ScheduleRule next,List<Occurrence> updated,List<Occurrence> canceled,List<Occurrence> revived,List<LocalDate> addedDates,List<UUID> affectedIds,int overwritten,int releaseCount,boolean affectsUnmaterialized){}
    private record DeletePlan(List<Occurrence> cancel,int preservedCompleted,int movedOverrides,boolean affectsUnmaterialized){}

    private void validateCreate(TaskDtos.CreateRequest request){
        if(!GENERAL_KINDS.contains(request.kind())&&!"MEDICATION".equals(request.kind()))throw validation("kind가 올바르지 않습니다.");
        validateRule(request.rule());
        if("MEDICATION".equals(request.kind())){
            if(request.medicationIds()==null||request.medicationIds().isEmpty())throw validation("MEDICATION에는 확정 처방이 필요합니다.");
            if(request.rule().lastDate()==null)throw validation("MEDICATION 일정에는 종료일이 필요합니다.");
        }else if(request.medicationIds()!=null&&!request.medicationIds().isEmpty())throw validation("일반 일정에는 medicationIds를 지정할 수 없습니다.");
    }
    private void validateRule(TaskDtos.Rule rule){
        if(!Set.of("ONCE","DAILY","WEEKLY").contains(rule.recurrence()))throw validation("recurrence가 올바르지 않습니다.");
        if(rule.lastDate()!=null&&rule.lastDate().isBefore(rule.firstDate()))throw validation("lastDate는 firstDate보다 빠를 수 없습니다.");
        if(rule.durationMinutes()<1||rule.durationMinutes()>1440)throw validation("durationMinutes는 1~1440이어야 합니다.");
        List<Integer> weekdays=rule.weekdays()==null?List.of():rule.weekdays();
        if(weekdays.stream().anyMatch(v->v<1||v>7)||new HashSet<>(weekdays).size()!=weekdays.size())throw validation("weekdays가 올바르지 않습니다.");
        if("ONCE".equals(rule.recurrence())&&(rule.lastDate()==null||!rule.firstDate().equals(rule.lastDate())||!weekdays.isEmpty()))throw validation("ONCE 규칙이 올바르지 않습니다.");
        if("DAILY".equals(rule.recurrence())&&!weekdays.isEmpty())throw validation("DAILY의 weekdays는 비어 있어야 합니다.");
        if("WEEKLY".equals(rule.recurrence())&&weekdays.isEmpty())throw validation("WEEKLY에는 요일이 필요합니다.");
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
