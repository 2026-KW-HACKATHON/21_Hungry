package com.kw.knowone.encounter.service;

import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.kw.knowone.encounter.repository.EncounterRepository;
import com.kw.knowone.task.repository.TaskRepository;
import com.kw.knowone.task.service.TaskGenerationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AutoTaskApplicationService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final EncounterRepository encounters;private final TaskRepository tasks;private final TaskGenerationService generator;
    private final ObjectMapper json;private final Clock clock;
    public AutoTaskApplicationService(EncounterRepository encounters,TaskRepository tasks,TaskGenerationService generator,ObjectMapper json,Clock clock){this.encounters=encounters;this.tasks=tasks;this.generator=generator;this.json=json;this.clock=clock;}
    public void applyReadyLocked(Encounter encounter){for(ReviewItem item:encounters.currentReviewItems(encounter.id())){
        if(!"TASK".equals(item.itemType())||!"READY".equals(item.reviewState()))continue;JsonNode p=json.readTree(item.payload());
            String kind=text(p,"kind"),title=text(p,"title"),recurrence=text(p,"recurrence");LocalDate first=LocalDate.parse(text(p,"date"));LocalTime time=LocalTime.parse(text(p,"time"));int duration=p.path("durationMinutes").asInt();
            if("PICKUP".equals(kind))kind="OTHER";
            String durationSource=text(p,"durationSource");if(!Set.of("HOSPITAL","EXAM","OTHER").contains(kind)||title==null||title.isBlank()||!Set.of("ONCE","DAILY","WEEKLY").contains(recurrence)||duration<1||duration>1440||durationSource==null)throw new IllegalStateException("READY task failed deterministic validation");
            if(!first.atTime(time).atZone(KST).toInstant().isAfter(clock.instant()))throw new IllegalStateException("READY task is no longer future-facing");List<Integer> weekdays=new ArrayList<>();JsonNode days=p.get("weekdays");if(days!=null&&days.isArray())for(JsonNode day:days)weekdays.add(day.asInt());
            LocalDate last="ONCE".equals(recurrence)?first:p.has("lastDate")&&!p.get("lastDate").isNull()?LocalDate.parse(p.get("lastDate").asText()):null;
            if("WEEKLY".equals(recurrence)&&weekdays.isEmpty()||!"WEEKLY".equals(recurrence)&&!weekdays.isEmpty())throw new IllegalStateException("READY task recurrence is invalid");
            UUID series=tasks.insertSourcedSeries(encounter.groupId(),encounter.createdBy(),kind,"ai-task:"+item.id(),item.id());
            tasks.insertRevision(series,encounter.groupId(),1,title,p.has("description")&&!p.get("description").isNull()?p.get("description").asText():null,
                    recurrence,first,last,weekdays,time,duration,encounter.createdBy(),clock.instant());
            if(!encounters.applyReviewItemAutomatically(item.id(),item.version(),clock.instant(),"ai-task:"+item.id()))throw new IllegalStateException("candidate changed");
            generator.generateSeriesLocked(series,UUID.randomUUID());}
    }
    private String text(JsonNode node,String key){JsonNode value=node.get(key);return value==null||value.isNull()?null:value.asText();}
}
