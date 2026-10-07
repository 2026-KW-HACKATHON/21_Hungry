package com.kw.knowone.encounter.processing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisItem;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisResult;
import com.kw.knowone.encounter.processing.AiProcessingPort.SourceText;

@Component
public class AnalysisOutputValidator {
    private static final Logger log=LoggerFactory.getLogger(AnalysisOutputValidator.class);
    private static final Set<String> ROOT_FIELDS=Set.of("schemaVersion","summary","details","evidence","items");
    private static final Set<String> ITEM_FIELDS=Set.of("itemKey","itemType","payload","evidence","uncertaintyCodes","isConditional");
    private static final Set<String> DETAIL_FIELDS=Set.of("symptoms","tests","medicationMentions","precautions","followUps");
    private static final Set<String> TASK_FIELDS=Set.of("schemaVersion","kind","title","description","date","time","durationMinutes","durationSource","recurrence","lastDate","weekdays");
    private static final Set<String> MEDICATION_FIELDS=Set.of("schemaVersion","name","doseText","frequencyText","startsOn","endsOn","instructions","supersedesMedicationId","schedulePlans");
    private static final Set<String> PLAN_FIELDS=Set.of("recurrence","firstDate","lastDate","weekdays","localTime","durationMinutes");
    private static final Set<String> EVIDENCE_FIELDS=Set.of("sourceId","textVersion","page","quote","occurrenceIndex");
    private static final Set<String> OCR_FIELDS=Set.of("schemaVersion","pages"),OCR_PAGE_FIELDS=Set.of("page","text");
    private static final Pattern DAYS_LATER=Pattern.compile("(\\d+)\\s*일\\s*(?:뒤|후)");
    private final ObjectMapper json;
    public AnalysisOutputValidator(ObjectMapper json){ this.json=json; }

    public AnalysisResult validate(String raw, AnalysisInput input, String provider, String model,
            String promptVersion, long inputTokens, long outputTokens) {
        try {
            JsonNode root=json.readTree(raw);
            require(root!=null&&root.isObject()&&hasExactly(root,ROOT_FIELDS)&&"1.0".equals(text(root,"schemaVersion")),"AI_SCHEMA_INVALID");
            require(root.has("summary")&&(root.get("summary").isNull()||root.get("summary").isTextual())&&root.has("details")&&root.get("details").isObject(),"AI_SCHEMA_INVALID");
            require(root.has("evidence")&&root.get("evidence").isArray(),"AI_SCHEMA_INVALID");
            require(root.has("items")&&root.get("items").isArray(),"AI_SCHEMA_INVALID");
            Map<String,SourceText> sources=new HashMap<>();
            for(SourceText source:input.sources()) sources.put(source.sourceId(),source);
            ArrayNode revisionEvidence=validateEvidence(root.get("evidence"),sources);
            validateDetails(root.get("details"),revisionEvidence.size());
            List<AnalysisItem> items=new ArrayList<>();Set<String> keys=new HashSet<>();
            for(JsonNode item:root.get("items")) {
                require(item.isObject()&&hasExactly(item,ITEM_FIELDS),"AI_SCHEMA_INVALID");
                String key=text(item,"itemKey"),type=text(item,"itemType");
                require(key!=null&&!key.isBlank()&&key.length()<=100&&keys.add(key),"AI_SCHEMA_INVALID");
                require(Set.of("TASK","MEDICATION").contains(type),"AI_SCHEMA_INVALID");
                JsonNode payload=item.get("payload");require(payload!=null&&payload.isObject(),"AI_SCHEMA_INVALID");
                List<String> reasons=new ArrayList<>();
                ArrayNode evidence;try{evidence=validateEvidence(item.get("evidence"),sources);}catch(AiProviderException invalid){
                    if(!"AI_EVIDENCE_INVALID".equals(invalid.code()))throw invalid;evidence=json.createArrayNode();reasons.add("INVALID_EVIDENCE");}
                JsonNode uncertainty=item.get("uncertaintyCodes");require(uncertainty.isArray(),"AI_SCHEMA_INVALID");Set<String> uncertaintyValues=new HashSet<>();
                for(JsonNode value:uncertainty){require(value.isTextual()&&!value.asText().isBlank()&&uncertaintyValues.add(value.asText()),"AI_SCHEMA_INVALID");reasons.add(value.asText());}
                require(item.get("isConditional").isBoolean(),"AI_SCHEMA_INVALID");
                boolean conditional=item.get("isConditional").asBoolean();
                if(conditional) reasons.add("CONDITIONAL_PLAN");
                if(evidence.isEmpty()) reasons.add("MISSING_EVIDENCE");
                if("MEDICATION".equals(type)) validateMedication(payload,reasons);
                else validateTask(payload,input,evidence,reasons);
                reasons=reasons.stream().distinct().sorted().toList();
                String state="TASK".equals(type)&&reasons.isEmpty()?"READY":"NEEDS_REVIEW";
                items.add(new AnalysisItem(key,type,json.writeValueAsString(payload),json.writeValueAsString(evidence),state,reasons));
            }
            items=applyCrossSourceMedicationConflicts(items);
            String summary=root.get("summary").isNull()?null:root.get("summary").asText();
            return new AnalysisResult(summary,json.writeValueAsString(root.get("details")),json.writeValueAsString(revisionEvidence),
                    items,provider,model,promptVersion,inputTokens,outputTokens);
        } catch(AiProviderException e){ throw e; }
        catch(RuntimeException e){ throw new AiProviderException("AI_SCHEMA_INVALID",false,e); }
    }

    public String validateOcr(String raw) {
        try {
            JsonNode root=json.readTree(raw);require(root!=null&&root.isObject()&&hasExactly(root,OCR_FIELDS)&&"1.0".equals(text(root,"schemaVersion")),"AI_SCHEMA_INVALID");
            JsonNode pages=root.get("pages");require(pages!=null&&pages.isArray()&&!pages.isEmpty(),"AI_SCHEMA_INVALID");
            StringBuilder result=new StringBuilder();int expected=1;
            for(JsonNode page:pages){require(page.isObject()&&hasExactly(page,OCR_PAGE_FIELDS)&&page.get("page").isIntegralNumber()&&page.get("page").asInt()==expected,"AI_SCHEMA_INVALID");
                JsonNode value=page.get("text");require(value!=null&&value.isTextual(),"AI_SCHEMA_INVALID");
                if(expected>1)result.append('\f');result.append(value.asText());expected++;}
            require(!result.toString().isBlank(),"AI_OUTPUT_INVALID");return result.toString();
        }catch(AiProviderException e){throw e;}catch(RuntimeException e){throw new AiProviderException("AI_SCHEMA_INVALID",false,e);}
    }

    private ArrayNode validateEvidence(JsonNode values,Map<String,SourceText> sources) {
        require(values!=null&&values.isArray(),"AI_SCHEMA_INVALID");ArrayNode result=json.createArrayNode();
        for(JsonNode value:values){
            require(value.isObject()&&hasExactly(value,EVIDENCE_FIELDS)&&value.get("textVersion").isIntegralNumber()&&value.get("occurrenceIndex").isIntegralNumber()&&(value.get("page").isNull()||value.get("page").isIntegralNumber()),"AI_SCHEMA_INVALID");
            String sourceId=text(value,"sourceId"),quote=text(value,"quote");int version=value.path("textVersion").asInt(-1);
            int occurrence=value.path("occurrenceIndex").asInt(-1);SourceText source=sources.get(sourceId);
            if(source==null||source.textVersion()!=version||quote==null||quote.isEmpty()||occurrence<0){
                evidenceInvalid("source_metadata",source,version,null,quote,occurrence);}
            Integer page=value.has("page")&&!value.get("page").isNull()?value.get("page").asInt():null;
            String pageText=source.text();int pageBase=0;
            if(page!=null){if(page<1||!"DOCUMENT".equals(source.sourceType()))evidenceInvalid("page_type",source,version,page,quote,occurrence);String[] pages=source.text().split("\\f",-1);if(page>pages.length)evidenceInvalid("page_range",source,version,page,quote,occurrence);
                pageText=pages[page-1];for(int i=0;i<page-1;i++)pageBase+=pages[i].length()+1;}
            int charIndex=nthIndex(pageText,quote,occurrence);if(charIndex<0)evidenceInvalid("quote_not_found",source,version,page,quote,occurrence);
            int absolute=pageBase+charIndex;int start=source.text().codePointCount(0,absolute);int end=start+quote.codePointCount(0,quote.length());
            ObjectNode enriched=(ObjectNode)value.deepCopy();enriched.put("start",start);enriched.put("end",end);result.add(enriched);
        }return result;
    }
    private void validateDetails(JsonNode details,int evidenceCount){require(hasExactly(details,DETAIL_FIELDS),"AI_SCHEMA_INVALID");for(String field:DETAIL_FIELDS){JsonNode values=details.get(field);require(values!=null&&values.isArray(),"AI_SCHEMA_INVALID");for(JsonNode value:values){require(value.isObject()&&value.size()==2&&value.has("text")&&value.has("evidenceIndexes")&&(value.get("text").isNull()||value.get("text").isTextual())&&value.get("evidenceIndexes").isArray(),"AI_SCHEMA_INVALID");Set<Integer> indexes=new HashSet<>();for(JsonNode index:value.get("evidenceIndexes")){int number=index.asInt(-1);require(index.isIntegralNumber()&&number>=0&&number<evidenceCount&&indexes.add(number),"AI_EVIDENCE_INVALID");}}}}
    private void validateTask(JsonNode p,AnalysisInput input,ArrayNode evidence,List<String> reasons){
        require(hasExactly(p,TASK_FIELDS)&&p.get("schemaVersion").isIntegralNumber()&&p.path("schemaVersion").asInt(-1)==1&&p.get("weekdays").isArray(),"AI_SCHEMA_INVALID");validateWeekdays(p.get("weekdays"));
        if(blank(p,"title"))reasons.add("MISSING_TITLE");if(blank(p,"kind"))reasons.add("MISSING_KIND");
        String date=text(p,"date"),time=text(p,"time"),recurrence=text(p,"recurrence");int duration=p.path("durationMinutes").asInt(0);
        String durationSource=text(p,"durationSource");if(durationSource==null)reasons.add("MISSING_DURATION_SOURCE");
        if("OTHER".equals(text(p,"kind"))&&!"USER_INPUT".equals(durationSource)&&!"SOURCE".equals(durationSource))reasons.add("OTHER_DURATION_REQUIRES_REVIEW");
        if(date==null||time==null||recurrence==null||duration<1){reasons.add("MISSING_SCHEDULE");return;}
        try{LocalDate parsedDate=LocalDate.parse(date);Instant start=parsedDate.atTime(java.time.LocalTime.parse(time)).atZone(ZoneId.of(input.timezone())).toInstant();
            if(!start.isAfter(input.analyzedAt()))reasons.add("PAST_OR_STARTED");JsonNode weekdays=p.get("weekdays");int weekdayCount=weekdays!=null&&weekdays.isArray()?weekdays.size():-1;
            if("ONCE".equals(recurrence)&&(!p.has("lastDate")||p.get("lastDate").isNull()||!date.equals(p.get("lastDate").asText())||weekdayCount!=0))reasons.add("INVALID_RECURRENCE");
            if("DAILY".equals(recurrence)&&weekdayCount!=0||"WEEKLY".equals(recurrence)&&weekdayCount<1||!Set.of("ONCE","DAILY","WEEKLY").contains(recurrence))reasons.add("INVALID_RECURRENCE");
            validateRelativeDate(evidence,input.occurredOn(),parsedDate,reasons);
        }catch(RuntimeException e){reasons.add("INVALID_SCHEDULE");}
    }
    private void validateMedication(JsonNode p,List<String> reasons){
        require(hasExactly(p,MEDICATION_FIELDS)&&p.get("schemaVersion").isIntegralNumber()&&p.path("schemaVersion").asInt(-1)==1,"AI_SCHEMA_INVALID");
        if(p.has("supersedesMedicationId")&&!p.get("supersedesMedicationId").isNull()){((ObjectNode)p).putNull("supersedesMedicationId");reasons.add("MODEL_DATABASE_ID_REJECTED");}
        for(String key:List.of("name","doseText","frequencyText","startsOn","endsOn"))if(blank(p,key))reasons.add("MISSING_"+key.toUpperCase());
        JsonNode plans=p.get("schedulePlans");if(plans==null||!plans.isArray()||plans.isEmpty())reasons.add("MISSING_TIME");
        else{Set<String> times=new HashSet<>();for(JsonNode plan:plans){require(plan.isObject()&&hasExactly(plan,PLAN_FIELDS)&&plan.get("weekdays").isArray()&&plan.get("durationMinutes").isIntegralNumber(),"AI_SCHEMA_INVALID");validateWeekdays(plan.get("weekdays"));if(blank(plan,"localTime")){reasons.add("MISSING_TIME");continue;}if(!times.add(text(plan,"localTime")))reasons.add("DUPLICATE_TIME");try{String recurrence=text(plan,"recurrence");LocalDate first=LocalDate.parse(text(plan,"firstDate")),last=LocalDate.parse(text(plan,"lastDate"));LocalDate starts=LocalDate.parse(text(p,"startsOn")),ends=LocalDate.parse(text(p,"endsOn"));int weekdayCount=plan.path("weekdays").size();if(last.isBefore(first)||first.isBefore(starts)||last.isAfter(ends)||"ONCE".equals(recurrence)&&(!first.equals(last)||weekdayCount!=0)||"DAILY".equals(recurrence)&&weekdayCount!=0||"WEEKLY".equals(recurrence)&&weekdayCount<1||!Set.of("ONCE","DAILY","WEEKLY").contains(recurrence))reasons.add("INVALID_MEDICATION_PLAN");java.time.LocalTime.parse(text(plan,"localTime"));}catch(RuntimeException invalid){reasons.add("INVALID_MEDICATION_PLAN");}}}
    }
    private void validateWeekdays(JsonNode values){Set<Integer> seen=new HashSet<>();for(JsonNode value:values){int day=value.asInt(-1);require(value.isIntegralNumber()&&day>=1&&day<=7&&seen.add(day),"AI_SCHEMA_INVALID");}}
    private List<AnalysisItem> applyCrossSourceMedicationConflicts(List<AnalysisItem> values){List<AnalysisItem> result=new ArrayList<>(values);for(int left=0;left<result.size();left++){AnalysisItem a=result.get(left);if(!"MEDICATION".equals(a.itemType()))continue;JsonNode ap=json.readTree(a.payloadJson());String name=text(ap,"name");if(name==null)continue;for(int right=left+1;right<result.size();right++){AnalysisItem b=result.get(right);if(!"MEDICATION".equals(b.itemType()))continue;JsonNode bp=json.readTree(b.payloadJson());if(!name.trim().equalsIgnoreCase(java.util.Objects.toString(text(bp,"name"),"" ).trim())||medicationFacts(ap).equals(medicationFacts(bp))||sourceIds(a.evidenceJson()).equals(sourceIds(b.evidenceJson())))continue;result.set(left,withReason(result.get(left),"CONFLICT"));result.set(right,withReason(result.get(right),"CONFLICT"));}}return result;}
    private String medicationFacts(JsonNode payload){return java.util.Objects.toString(text(payload,"doseText"),"")+"|"+java.util.Objects.toString(text(payload,"frequencyText"),"")+"|"+java.util.Objects.toString(text(payload,"startsOn"),"")+"|"+java.util.Objects.toString(text(payload,"endsOn"),"");}
    private Set<String> sourceIds(String evidenceJson){Set<String> result=new HashSet<>();for(JsonNode evidence:json.readTree(evidenceJson))result.add(java.util.Objects.toString(text(evidence,"sourceId"),""));return result;}
    private AnalysisItem withReason(AnalysisItem item,String reason){List<String> reasons=new ArrayList<>(item.reviewReasons());reasons.add(reason);reasons=reasons.stream().distinct().sorted().toList();return new AnalysisItem(item.itemKey(),item.itemType(),item.payloadJson(),item.evidenceJson(),"NEEDS_REVIEW",reasons);}
    private void validateRelativeDate(ArrayNode evidence,LocalDate occurredOn,LocalDate candidate,List<String> reasons){for(JsonNode value:evidence){String quote=text(value,"quote");if(quote==null)continue;LocalDate expected=null;if(quote.contains("내일"))expected=occurredOn==null?null:occurredOn.plusDays(1);else if(quote.contains("일주일 뒤")||quote.contains("일주일 후"))expected=occurredOn==null?null:occurredOn.plusDays(7);else{Matcher matcher=DAYS_LATER.matcher(quote);if(matcher.find()&&occurredOn!=null)expected=occurredOn.plusDays(Long.parseLong(matcher.group(1)));}if(quote.contains("다음 주")&&!quote.matches(".*(월|화|수|목|금|토|일)요일.*")){reasons.add("AMBIGUOUS_RELATIVE_DATE");continue;}if(expected==null&&(quote.contains("내일")||quote.contains("뒤")||quote.contains("후")||quote.contains("다음 주")))reasons.add("RELATIVE_DATE_WITHOUT_BASE");else if(expected!=null&&!expected.equals(candidate))reasons.add("RELATIVE_DATE_MISMATCH");}}
    private boolean hasExactly(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.size()!=fields.size())return false;return fields.stream().allMatch(node::has);}
    private boolean blank(JsonNode node,String key){String value=text(node,key);return value==null||value.isBlank();}
    private String text(JsonNode node,String key){JsonNode value=node==null?null:node.get(key);return value==null||value.isNull()||!value.isTextual()?null:value.asText();}
    private int nthIndex(String text,String quote,int occurrence){int from=0,index=-1;for(int i=0;i<=occurrence;i++){index=text.indexOf(quote,from);if(index<0)return -1;from=index+quote.length();}return index;}
    private void evidenceInvalid(String reason,SourceText source,int version,Integer page,String quote,int occurrence){
        log.warn("AI evidence rejected reason={} sourceType={} mediaType={} expectedVersion={} suppliedVersion={} page={} pageCount={} quoteCodePoints={} occurrence={}",
                reason,source==null?"missing":source.sourceType(),source==null?"missing":source.mediaType(),source==null?-1:source.textVersion(),version,page,
                source==null?0:source.text().split("\\f",-1).length,quote==null?0:quote.codePointCount(0,quote.length()),occurrence);
        throw new AiProviderException("AI_EVIDENCE_INVALID",false);
    }
    private void require(boolean value,String code){if(!value)throw new AiProviderException(code,false);}
}
