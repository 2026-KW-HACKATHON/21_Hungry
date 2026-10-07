package com.kw.knowone.review.service;

import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.kw.knowone.encounter.repository.EncounterRepository;
import com.kw.knowone.group.repository.GroupRepository;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.review.dto.ReviewDtos;
import com.kw.knowone.task.dto.TaskDtos;
import com.kw.knowone.task.repository.TaskRepository;
import com.kw.knowone.task.service.TaskGenerationService;
import com.kw.knowone.task.service.TaskService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReviewService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final EncounterRepository encounters;private final TaskRepository tasks;private final GroupRepository groups;
    private final ScheduleMutationService mutations;private final TaskGenerationService generator;private final TaskService taskService;
    private final ObjectMapper json;private final Clock clock;private final CursorService cursors;private final GroupEventRepository events;
    public ReviewService(EncounterRepository encounters,TaskRepository tasks,GroupRepository groups,ScheduleMutationService mutations,
            TaskGenerationService generator,TaskService taskService,ObjectMapper json,Clock clock,CursorService cursors,GroupEventRepository events){this.encounters=encounters;this.tasks=tasks;this.groups=groups;this.mutations=mutations;this.generator=generator;this.taskService=taskService;this.json=json;this.clock=clock;this.cursors=cursors;this.events=events;}

    public ReviewDtos.ReviewItems items(UUID encounterId,UUID userId,String state){Encounter encounter=requireEncounter(encounterId,userId);
        String filter=state==null?"NEEDS_REVIEW":state;if(!Set.of("ALL","NEEDS_REVIEW","READY","APPLIED","DISMISSED","SUPERSEDED").contains(filter))throw validation("reviewState가 올바르지 않습니다.");
        Revision revision=encounters.currentRevision(encounterId).orElse(null);List<ReviewDtos.ReviewItem> values=encounters.currentReviewItems(encounterId).stream()
                .filter(v->"ALL".equals(filter)||filter.equals(v.reviewState())).map(this::dto).toList();
        return new ReviewDtos.ReviewItems(values,encounter.inputVersion(),revision==null?null:revision.id(),revision!=null&&revision.inputVersion()==encounter.inputVersion());}

    public IdempotentResult dismiss(UUID encounterId,UUID itemId,UUID userId,ReviewDtos.DismissRequest request,String key){return dismiss(encounterId,itemId,userId,request,key,UUID.randomUUID());}
    public IdempotentResult dismiss(UUID encounterId,UUID itemId,UUID userId,ReviewDtos.DismissRequest request,String key,UUID requestId){return mutations.execute(userId,"R03:"+itemId,key,request,
            ()->requireEncounter(encounterId,userId),()->{ReviewItem item=encounters.lockCurrentReviewItem(encounterId,itemId);if(item==null)throw notFound();
                if(item.version()!=request.expectedVersion())throw version(item.version());if(!Set.of("NEEDS_REVIEW","READY").contains(item.reviewState()))throw invalid("확인 대기 후보만 제외할 수 있습니다.");
                if(!encounters.dismissReviewItem(item.id(),item.version(),userId,clock.instant()))throw version(item.version());
                UUID groupId=requireEncounter(encounterId,userId).groupId();events.audit(groupId,userId,"REVIEW_ITEM_DISMISSED","EXTRACTED_ITEM",item.id(),Map.of("reviewState",item.reviewState(),"version",item.version()),Map.of("reviewState","DISMISSED","version",item.version()+1),requestId);
                return new MutationResponse(200,DataResponse.of(new ReviewDtos.Dismissed(item.id(),"DISMISSED",item.version()+1)));});}

    public IdempotentResult confirm(UUID encounterId,UUID userId,ReviewDtos.ConfirmRequest request,String key){return confirm(encounterId,userId,request,key,UUID.randomUUID());}
    public IdempotentResult confirm(UUID encounterId,UUID userId,ReviewDtos.ConfirmRequest request,String key,UUID requestId){return mutations.execute(userId,"R02:"+encounterId,key,request,
            ()->requireEncounter(encounterId,userId),()->confirmLocked(encounterId,userId,request,requestId));}

    private MutationResponse confirmLocked(UUID encounterId,UUID userId,ReviewDtos.ConfirmRequest request,UUID requestId){Encounter encounter=encounters.lockActive(encounterId);if(encounter==null)throw notFound();
        requireMember(encounter.groupId(),userId);if(encounter.inputVersion()!=request.expectedInputVersion())throw version(encounter.inputVersion());
        Revision revision=encounters.currentRevision(encounterId).orElseThrow(this::notFound);if(!revision.id().equals(request.revisionId())||revision.inputVersion()!=encounter.inputVersion())throw reviewRequired();
        Set<UUID> ids=new HashSet<>();if(request.items().stream().anyMatch(v->!ids.add(v.itemId())))throw validation("itemId는 중복될 수 없습니다.");
        Instant now=clock.instant();LocalDate today=LocalDate.now(clock);List<AppliedMedication> applied=new ArrayList<>();List<UUID> appliedIds=new ArrayList<>();Set<String> medicationSignatures=new HashSet<>();
        for(ReviewDtos.ConfirmItem requested:request.items()){ReviewItem item=encounters.lockCurrentReviewItem(encounterId,requested.itemId());if(item==null||!item.revisionId().equals(revision.id()))throw reviewRequired();
            if(item.version()!=requested.expectedVersion())throw version(item.version());if(!Set.of("NEEDS_REVIEW","READY").contains(item.reviewState()))throw invalid("이미 처리된 후보입니다.");
            JsonNode payload=requested.payload()==null?read(item.payload()):requested.payload();validateConflict(encounterId,item,requested,payload);
            if(!"MEDICATION".equals(item.itemType()))throw validation("TASK 후보 확인은 자동 적용 정책 또는 일정 화면에서 처리합니다.");
            MedicationPayload medication=medication(payload,encounter.groupId(),today);if(!medicationSignatures.add(medication.signature()))throw new ApiException(HttpStatus.CONFLICT,"DUPLICATE_MEDICATION_CONFLICT","같은 처방 후보가 요청에 중복되어 있습니다.");UUID medicationId=encounters.insertMedication(encounter.groupId(),item.id(),medication.supersedesId(),medication.name(),medication.dose(),medication.frequency(),medication.starts(),medication.ends(),medication.instructions(),userId);
            if(!encounters.applyReviewItem(item.id(),item.version(),userId,now,json.writeValueAsString(payload),"review:"+item.id()))throw version(item.version());
            events.audit(encounter.groupId(),userId,"MEDICATION_CONFIRMED","MEDICATION_ORDER",medicationId,null,Map.of("sourceItemId",item.id(),"supersedesId",medication.supersedesId()==null?"":medication.supersedesId().toString()),requestId);
            events.notification(encounter.groupId(),"MEDICATION_CONFIRMED","medication-confirmed:"+item.id(),Map.of("schemaVersion",1,"encounterId",encounterId,"itemId",item.id()),now);
            applied.add(new AppliedMedication(item.id(),medicationId,medication));appliedIds.add(item.id());}
        Map<PlanKey,PlanGroup> plans=new LinkedHashMap<>();for(AppliedMedication medication:applied)for(Plan plan:medication.payload().plans()){
            PlanKey planKey=new PlanKey(plan.recurrence(),plan.weekdays(),plan.time(),plan.duration());PlanGroup group=plans.computeIfAbsent(planKey,k->new PlanGroup(plan.first(),plan.last(),new ArrayList<>(),new ArrayList<>()));
            group.first=group.first.isBefore(plan.first())?group.first:plan.first();group.last=group.last.isAfter(plan.last())?group.last:plan.last();group.medicationIds.add(medication.medicationId());group.itemIds.add(medication.itemId());}
        List<UUID> seriesIds=new ArrayList<>(),occurrenceIds=new ArrayList<>();for(var entry:plans.entrySet()){PlanKey p=entry.getKey();PlanGroup g=entry.getValue();var existing=tasks.findMedicationSeries(encounter.groupId(),p.recurrence(),p.weekdays(),p.time(),p.duration());UUID seriesId;int revisionNo;
            if(existing.isPresent()){var series=existing.get();seriesId=series.id();revisionNo=series.currentRevisionNo()+1;LocalDate first=series.firstDate().isBefore(g.first)?series.firstDate():g.first;LocalDate last=series.lastDate().isAfter(g.last)?series.lastDate():g.last;
                tasks.insertRevision(seriesId,encounter.groupId(),revisionNo,series.title(),series.description(),p.recurrence(),first,last,p.weekdays(),p.time(),p.duration(),userId,now);tasks.copySeriesMedications(encounter.groupId(),seriesId,series.currentRevisionNo(),revisionNo);
                for(UUID medicationId:g.medicationIds.stream().distinct().toList())tasks.linkSeriesMedication(encounter.groupId(),seriesId,revisionNo,medicationId);if(tasks.advanceSeries(seriesId,series.version(),revisionNo)!=1)throw version(series.version());tasks.refreshFutureMedicationSnapshots(encounter.groupId(),seriesId,revisionNo,now);
            }else{seriesId=tasks.insertSeries(encounter.groupId(),userId,"MEDICATION","review:"+encounterId+":"+revision.id()+":"+p.key());revisionNo=1;
                tasks.insertRevision(seriesId,encounter.groupId(),1,p.time()+" 복약 챙기기",null,p.recurrence(),g.first,g.last,p.weekdays(),p.time(),p.duration(),userId,now);
                for(UUID medicationId:g.medicationIds.stream().distinct().toList())tasks.linkSeriesMedication(encounter.groupId(),seriesId,1,medicationId);}
            seriesIds.add(seriesId);occurrenceIds.addAll(generator.generateSeriesLocked(seriesId,UUID.randomUUID()));}
        for(AppliedMedication medication:applied)if(medication.payload().supersedesId()!=null)for(var series:tasks.seriesForMedication(medication.payload().supersedesId()))tasks.refreshFutureMedicationSnapshots(encounter.groupId(),series.id(),series.currentRevisionNo(),now);
        List<UUID> medicationIds=applied.stream().map(AppliedMedication::medicationId).toList();List<TaskDtos.Medication> medications=tasks.medicationsByIds(encounter.groupId(),medicationIds);
        occurrenceIds=tasks.pendingOccurrenceIdsForMedications(medicationIds,now);List<TaskDtos.Task> occurrences=occurrenceIds.stream().map(id->taskService.detail(id,userId)).toList();
        return new MutationResponse(200,DataResponse.of(new ReviewDtos.Confirmed(appliedIds,medications,seriesIds,occurrences,encounter.inputVersion())));}

    public ReviewDtos.MedicationPage medications(UUID groupId,UUID userId,UUID encounterId,LocalDate onDate,Integer requestedLimit,String cursor){requireMember(groupId,userId);
        if(encounterId!=null){Encounter encounter=encounters.findActive(encounterId).orElseThrow(this::notFound);if(!encounter.groupId().equals(groupId))throw forbidden();}
        int limit=requestedLimit==null?20:requestedLimit;if(limit<1||limit>100)throw validation("limit은 1~100이어야 합니다.");CursorService.Value value=cursors.decode(cursor);
        LocalDate cursorDate=value==null?null:value.time().atZone(KST).toLocalDate();List<TaskDtos.Medication> rows=encounters.medications(groupId,encounterId,onDate,cursorDate,value==null?null:value.id(),limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);String next=more?cursors.encode(rows.getLast().startsOn().atStartOfDay(KST).toInstant(),rows.getLast().id()):null;
        return new ReviewDtos.MedicationPage(rows,next,more);}

    private MedicationPayload medication(JsonNode p,UUID groupId,LocalDate today){require(p!=null&&p.isObject()&&p.size()==9&&p.path("schemaVersion").asInt(-1)==1&&Set.of("schemaVersion","name","doseText","frequencyText","startsOn","endsOn","instructions","supersedesMedicationId","schedulePlans").stream().allMatch(p::has),"payload schemaVersion=1 전체 필드가 필요합니다.");String name=required(p,"name"),dose=required(p,"doseText"),frequency=required(p,"frequencyText");
        LocalDate starts=date(p,"startsOn"),ends=date(p,"endsOn");if(ends.isBefore(starts))throw validation("처방 종료일이 시작일보다 빠릅니다.");String instructions=nullable(p,"instructions");UUID supersedes=uuid(p,"supersedesMedicationId");
        if(supersedes!=null){if(!encounters.medicationBelongsToGroup(supersedes,groupId))throw validation("변경 대상 처방이 같은 공동체에 없습니다.");if(starts.isBefore(today))throw validation("변경 처방은 오늘 이후에만 적용할 수 있습니다.");}
        JsonNode values=p.get("schedulePlans");if(values==null||!values.isArray()||values.isEmpty())throw validation("명시 시각 schedulePlans가 필요합니다.");List<Plan> plans=new ArrayList<>();Set<LocalTime> times=new HashSet<>();
        for(JsonNode value:values){require(value.isObject()&&value.size()==6&&Set.of("recurrence","firstDate","lastDate","weekdays","localTime","durationMinutes").stream().allMatch(value::has),"schedulePlans 전체 필드가 필요합니다.");String recurrence=required(value,"recurrence");if(!Set.of("ONCE","DAILY","WEEKLY").contains(recurrence))throw validation("반복 규칙이 올바르지 않습니다.");
            LocalDate first=date(value,"firstDate"),last=date(value,"lastDate");if(first.isBefore(starts)||last.isAfter(ends)||last.isBefore(first))throw validation("일정 기간은 처방 기간 안이어야 합니다.");LocalTime time=time(value,"localTime");if(!times.add(time))throw validation("중복 복약 시각은 허용하지 않습니다.");
            List<Integer> weekdays=new ArrayList<>();JsonNode weekdayValues=value.get("weekdays");if(weekdayValues!=null&&weekdayValues.isArray())for(JsonNode day:weekdayValues)weekdays.add(day.asInt());
            if(weekdayValues==null||!weekdayValues.isArray()||weekdays.size()!=weekdays.stream().distinct().count())throw validation("weekdays가 올바르지 않습니다.");if("WEEKLY".equals(recurrence)&&(weekdays.isEmpty()||weekdays.stream().anyMatch(d->d<1||d>7)))throw validation("WEEKLY 요일이 필요합니다.");if(!"WEEKLY".equals(recurrence)&&!weekdays.isEmpty())throw validation("요일은 WEEKLY에만 사용합니다.");if("ONCE".equals(recurrence)&&!first.equals(last))throw validation("ONCE 계획의 시작일과 종료일은 같아야 합니다.");
            JsonNode durationValue=value.get("durationMinutes");int duration=durationValue!=null&&durationValue.isIntegralNumber()?durationValue.asInt():0;if(duration<1||duration>1440)throw validation("durationMinutes가 올바르지 않습니다.");plans.add(new Plan(recurrence,first,last,weekdays.stream().distinct().sorted().toList(),time,duration));}
        return new MedicationPayload(name,dose,frequency,starts,ends,instructions,supersedes,plans);}
    private void validateConflict(UUID encounterId,ReviewItem item,ReviewDtos.ConfirmItem requested,JsonNode payload){boolean conflict=List.of(item.reviewReasons()).contains("CONFLICT");if(!conflict)return;JsonNode resolution=requested.conflictResolution();String mode=resolution==null?null:nullable(resolution,"mode");
        if("MANUAL".equals(mode)){if(requested.payload()==null||nullable(resolution,"note")==null)throw reviewRequired();return;}if("SOURCE".equals(mode)){UUID sourceId=uuid(resolution,"selectedSourceId");Source source=sourceId==null?null:encounters.activeSource(sourceId).orElse(null);if(source==null||!source.encounterId().equals(encounterId)||!item.evidence().contains(sourceId.toString())||!sourceSupportsPayload(source.extractedText(),payload))throw reviewRequired();return;}throw reviewRequired();}
    private boolean sourceSupportsPayload(String source,JsonNode payload){if(source==null)return false;for(String key:List.of("name","doseText","frequencyText")){String value=nullable(payload,key);if(value!=null&&!source.contains(value))return false;}return true;}
    private ReviewDtos.ReviewItem dto(ReviewItem v){return new ReviewDtos.ReviewItem(v.id(),v.encounterId(),v.revisionId(),v.itemType(),v.reviewState(),List.of(v.reviewReasons()),read(v.payload()),evidenceDto(v.evidence()),v.reviewedBy()==null?null:new TaskDtos.UserRef(v.reviewedBy(),v.reviewerName()),v.reviewedAt()==null?null:v.reviewedAt().atZone(KST).toOffsetDateTime(),v.version());}
    private JsonNode evidenceDto(String raw){JsonNode values=read(raw);var result=json.createArrayNode();if(values==null||!values.isArray())return result;for(JsonNode value:values){var out=json.createObjectNode();String sourceId=nullable(value,"sourceId");out.put("sourceId",sourceId);out.put("textVersion",value.path("textVersion").asInt());if(value.has("page")&&!value.get("page").isNull())out.put("page",value.get("page").asInt());else out.putNull("page");if(value.has("start"))out.put("startOffset",value.get("start").asInt());else out.putNull("startOffset");if(value.has("end"))out.put("endOffset",value.get("end").asInt());else out.putNull("endOffset");boolean stale=true;try{Source source=encounters.activeSource(UUID.fromString(sourceId)).orElse(null);stale=source==null||source.textVersion()!=value.path("textVersion").asInt();}catch(RuntimeException ignored){}out.put("isStale",stale);result.add(out);}return result;}
    private Encounter requireEncounter(UUID id,UUID userId){Encounter value=encounters.findActive(id).orElseThrow(this::notFound);requireMember(value.groupId(),userId);return value;}
    private void requireMember(UUID groupId,UUID userId){if(groups.findActiveMembership(groupId,userId).isEmpty())throw forbidden();}
    private JsonNode read(String value){return json.readTree(value);}
    private String required(JsonNode n,String key){String value=nullable(n,key);if(value==null||value.isBlank())throw validation(key+"가 필요합니다.");return value;}
    private String nullable(JsonNode n,String key){JsonNode v=n==null?null:n.get(key);return v==null||v.isNull()?null:v.asText();}
    private LocalDate date(JsonNode n,String key){try{return LocalDate.parse(required(n,key));}catch(RuntimeException e){throw validation(key+"가 올바르지 않습니다.");}}
    private LocalTime time(JsonNode n,String key){try{return LocalTime.parse(required(n,key));}catch(RuntimeException e){throw validation(key+"가 올바르지 않습니다.");}}
    private UUID uuid(JsonNode n,String key){String value=nullable(n,key);try{return value==null?null:UUID.fromString(value);}catch(RuntimeException e){throw validation(key+"가 올바르지 않습니다.");}}
    private void require(boolean ok,String message){if(!ok)throw validation(message);}
    private ApiException validation(String m){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION_ERROR",m);}
    private ApiException version(long current){return new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","후보가 변경되었습니다.",Map.of("currentVersion",current));}
    private ApiException reviewRequired(){return new ApiException(HttpStatus.CONFLICT,"REVIEW_REQUIRED","최신 후보와 충돌 해소를 다시 확인해 주세요.");}
    private ApiException invalid(String m){return new ApiException(HttpStatus.CONFLICT,"INVALID_STATE",m);}
    private ApiException forbidden(){return new ApiException(HttpStatus.FORBIDDEN,"NOT_MEMBER","공동체의 ACTIVE 구성원이 아닙니다.");}
    private ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","대상을 찾을 수 없습니다.");}
    private record MedicationPayload(String name,String dose,String frequency,LocalDate starts,LocalDate ends,String instructions,UUID supersedesId,List<Plan> plans){String signature(){return name+"|"+dose+"|"+frequency+"|"+starts+"|"+ends+"|"+supersedesId+"|"+plans.stream().map(Plan::toString).sorted().toList();}}
    private record Plan(String recurrence,LocalDate first,LocalDate last,List<Integer> weekdays,LocalTime time,int duration){ }
    private record AppliedMedication(UUID itemId,UUID medicationId,MedicationPayload payload){ }
    private record PlanKey(String recurrence,List<Integer> weekdays,LocalTime time,int duration){String key(){return recurrence+":"+weekdays+":"+time+":"+duration;}}
    private static final class PlanGroup {private LocalDate first,last;private final List<UUID> medicationIds,itemIds;private PlanGroup(LocalDate first,LocalDate last,List<UUID> medicationIds,List<UUID> itemIds){this.first=first;this.last=last;this.medicationIds=medicationIds;this.itemIds=itemIds;}}
}
