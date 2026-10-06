package com.kw.knowone.task.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import org.springframework.stereotype.Service;
import com.kw.knowone.availability.repository.AvailabilityRepository;
import com.kw.knowone.availability.service.AvailabilityService;
import com.kw.knowone.task.entity.TaskModels.Candidate;
import com.kw.knowone.task.repository.TaskRepository;

@Service
public class ScheduleAssignmentService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final TaskRepository tasks;
    private final AvailabilityRepository availability;
    private final AvailabilityService availabilityService;

    public ScheduleAssignmentService(TaskRepository tasks, AvailabilityRepository availability,
            AvailabilityService availabilityService) {
        this.tasks = tasks; this.availability = availability; this.availabilityService = availabilityService;
    }

    public UUID select(UUID groupId, Instant startsAt, Instant endsAt) {
        ZonedDateTime local = startsAt.atZone(KST);
        LocalDate monday = local.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        Instant weekStart = monday.atStartOfDay(KST).toInstant();
        Instant weekEnd = monday.plusDays(7).atStartOfDay(KST).toInstant();
        return eligible(groupId, startsAt, endsAt).stream()
                .map(candidate -> new Scored(candidate,
                        tasks.weeklyCount(groupId, candidate.userId(), weekStart, weekEnd)))
                .min(Comparator.comparingInt((Scored value) -> value.candidate().priority())
                        .thenComparingInt(Scored::weeklyCount)
                        .thenComparing(value -> value.candidate().memberId().toString()))
                .map(value -> value.candidate().userId()).orElse(null);
    }

    public List<Candidate> eligible(UUID groupId, Instant startsAt, Instant endsAt) {
        return tasks.candidates(groupId).stream()
                .filter(value -> availabilityService.isAvailable(value.userId(), startsAt, endsAt))
                .filter(value -> !availability.hasConflict(value.userId(), startsAt, endsAt, null)).toList();
    }

    public List<AssignmentDecision> assignNew(List<Occurrence> occurrences) {
        List<AssignmentDecision> decisions=new ArrayList<>();
        Map<String,List<Occurrence>> byDay=new LinkedHashMap<>();
        occurrences.stream().sorted(Comparator.comparing(Occurrence::startsAt)
                        .thenComparing(value->value.seriesId().toString()).thenComparing(Occurrence::anchorDate)
                        .thenComparing(value->value.id().toString()))
                .forEach(value->byDay.computeIfAbsent(value.groupId()+":"+value.startsAt().atZone(KST).toLocalDate(),ignored->new ArrayList<>()).add(value));
        for(List<Occurrence> day:byDay.values()){
            int index=0;
            while(index<day.size()){
                Occurrence first=day.get(index);List<Occurrence> cluster=new ArrayList<>();cluster.add(first);int next=index+1;
                Instant lastEnd=first.endsAt();
                while(next<day.size()&&!day.get(next).startsAt().isAfter(first.startsAt().plus(java.time.Duration.ofMinutes(240)))){
                    Occurrence candidate=day.get(next);
                    if(candidate.startsAt().isBefore(lastEnd))break;
                    cluster.add(candidate);lastEnd=candidate.endsAt();next++;
                }
                UUID bundled=cluster.size()>1?assignBundle(cluster):null;
                if(cluster.size()>1&&bundled==null){
                    for(Occurrence value:cluster)decisions.add(assignOne(value));
                }else if(cluster.size()==1)decisions.add(assignOne(first));
                else decisions.add(new AssignmentDecision(first.groupId(),bundled,cluster.stream().map(Occurrence::id).toList(),true));
                index=next;
            }
        }
        return decisions;
    }

    private UUID assignBundle(List<Occurrence> cluster){
        Set<UUID> intersection=null;Map<UUID,Candidate> candidatesByUser=new java.util.HashMap<>();
        for(Occurrence task:cluster){
            List<Candidate> eligible=eligible(task.groupId(),task.startsAt(),task.endsAt());
            if(eligible.isEmpty())return null;
            int top=eligible.stream().mapToInt(Candidate::priority).min().orElseThrow();
            List<Candidate> ranked=eligible.stream().filter(value->value.priority()==top).toList();
            int min=ranked.stream().mapToInt(value->weeklyCount(task.groupId(),value.userId(),task.startsAt())).min().orElseThrow();
            Set<UUID> best=new HashSet<>();
            for(Candidate value:ranked)if(weeklyCount(task.groupId(),value.userId(),task.startsAt())==min){best.add(value.userId());candidatesByUser.put(value.userId(),value);}
            if(intersection==null)intersection=new HashSet<>(best);else intersection.retainAll(best);
            if(intersection.isEmpty())return null;
        }
        Instant from=cluster.getFirst().startsAt();Instant to=cluster.getLast().endsAt();
        UUID selected=intersection.stream().filter(user->isAvailable(user,from,to)&&!hasConflict(user,from,to,null))
                .min(Comparator.comparing(user->candidatesByUser.get(user).memberId().toString())).orElse(null);
        if(selected==null)return null;
        for(Occurrence value:cluster)if(tasks.assignAuto(value.id(),selected)!=1)throw new IllegalStateException("Concurrent automatic assignment");
        return selected;
    }

    private AssignmentDecision assignOne(Occurrence value){UUID selected=select(value.groupId(),value.startsAt(),value.endsAt());
        if(selected!=null&&tasks.assignAuto(value.id(),selected)!=1)throw new IllegalStateException("Concurrent automatic assignment");
        return new AssignmentDecision(value.groupId(),selected,List.of(value.id()),false);}

    private int weeklyCount(UUID groupId,UUID userId,Instant startsAt){
        LocalDate monday=startsAt.atZone(KST).toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return tasks.weeklyCount(groupId,userId,monday.atStartOfDay(KST).toInstant(),monday.plusDays(7).atStartOfDay(KST).toInstant());
    }

    public boolean canAssign(UUID userId, Instant startsAt, Instant endsAt, UUID excludedOccurrence) {
        return isAvailable(userId, startsAt, endsAt)
                && !hasConflict(userId, startsAt, endsAt, excludedOccurrence);
    }

    public boolean isAvailable(UUID userId, Instant startsAt, Instant endsAt) {
        return availabilityService.isAvailable(userId, startsAt, endsAt);
    }

    public boolean hasConflict(UUID userId, Instant startsAt, Instant endsAt, UUID excludedOccurrence) {
        return availability.hasConflict(userId, startsAt, endsAt, excludedOccurrence);
    }

    private record Scored(Candidate candidate, int weeklyCount) { }
    public record AssignmentDecision(UUID groupId,UUID assigneeUserId,List<UUID> occurrenceIds,boolean bundled) { }
}
