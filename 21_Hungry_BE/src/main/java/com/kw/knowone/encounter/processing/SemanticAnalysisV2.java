package com.kw.knowone.encounter.processing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;

/** Opt-in v1.8.1 extraction protocol. Historical v1.8 is immutable. Checks structural contradictions, NOT medical entailment. */
final class SemanticAnalysisV2 {
    static final String PROTOCOL="semantic-facts-v2";
    private final ObjectMapper json;
    private final UtteranceEvidence catalog;
    private final Map<String,UtteranceEvidence.Span> spans=new LinkedHashMap<>();
    SemanticAnalysisV2(ObjectMapper json,UtteranceEvidence catalog) {
        this.json=json;this.catalog=catalog;
        catalog.spans().forEach(s->spans.put(s.id(),s));
    }
    JsonNode schema() {
        try {
            var root=(ObjectNode)json.readTree(new ClassPathResource("ai/analysis-semantic-facts.schema.json").getContentAsString(StandardCharsets.UTF_8));
            ((ObjectNode)root.at("/properties/schemaVersion")).put("const","1.8.1");
            ((ObjectNode)root.at("/$defs/utteranceId")).set("enum",json.valueToTree(spans.keySet()));
            return root;
        } catch(IOException e){throw new IllegalStateException("Missing semantic schema",e);}
    }
    String resolve(String raw) {
        try {
            var checked=inspect(raw);
            if(!checked.failures().isEmpty())throw new AiProviderException(checked.failures().getFirst().code(),false);
            var in=checked.normalized();
            var byId=new LinkedHashMap<String,JsonNode>();var evidence=new HashMap<String,ArrayNode>();
            var alternatives=new HashSet<String>();
            for(var fact:in.path("facts")) {
                String id=fact.path("factId").asText();
                require(!id.isBlank()&&byId.putIfAbsent(id,fact)==null,"AI_SCHEMA_INVALID");
                require(!fact.path("text").asText().isBlank(),"AI_SCHEMA_INVALID");
                evidence.put(id,support((ObjectNode)fact.path("support"),new ArrayList<>(),!fact.path("task").isNull()));
                validateRelations(fact,alternatives);
            }
            var out=json.createObjectNode().put("schemaVersion","1.0");
            var summary=out.putArray("summary");var selected=new HashSet<String>();
            for(var id:in.path("summaryFactIds")) {
                var fact=byId.get(id.asText());
                require(fact!=null&&selected.add(id.asText()),"AI_EVIDENCE_INVALID");
                summary.add(claim(fact,evidence.get(id.asText())));
            }
            require(byId.isEmpty()||!summary.isEmpty(),"AI_SEMANTIC_INVALID");
            var details=out.putObject("details");
            UtteranceEvidence.DETAIL_FIELDS.forEach(details::putArray);
            var items=out.putArray("items");
            for(var fact:byId.values()) {
                String id=fact.path("factId").asText(),kind=fact.path("kind").asText();
                // No public details field for assessment: preserve it in summary even if not selected.
                if(kind.equals("ASSESSMENT")&&!selected.contains(id))summary.add(claim(fact,evidence.get(id)));
                String field=switch(kind){case "SYMPTOM"->"symptoms";case "TEST"->"tests";case "MEDICATION"->"medicationMentions";case "PRECAUTION"->"precautions";case "FOLLOW_UP"->"followUps";default->null;};
                if(field!=null)((ArrayNode)details.get(field)).add(claim(fact,evidence.get(id)));
                String role=fact.path("medicationRole").asText();
                if(kind.equals("MEDICATION")&&!fact.path("medication").isNull()&&!Set.of("EXAMPLE","HISTORICAL","SUPERSEDED").contains(role))
                    items.add(medication(fact,evidence.get(id)));
                if(fact.path("actionStatus").asText().equals("FUTURE")&&!fact.path("task").isNull())items.add(task(fact,evidence.get(id)));
            }
            // Existing quote/position resolver and external validator still run; no invalid evidence deletion.
            return catalog.resolve(json.writeValueAsString(out),true);
        }catch(AiProviderException e){throw e;}catch(RuntimeException e){throw new AiProviderException("AI_SCHEMA_INVALID",false,e);}
    }
    record Failure(String factId,String path,String code) {}
    record Repair(String factId,String code,String utteranceId) {}
    record Inspection(JsonNode normalized,List<Failure> failures,List<Repair> repairs) {}
    /** Diagnostic result is NOT a partial successful AnalysisResult. No candidates are applied on failure. */
    Inspection inspect(String raw) {
        JsonNode in;
        try {in=json.readTree(raw);}catch(RuntimeException e) {
            return new Inspection(json.nullNode(),List.of(new Failure("","/","AI_JSON_INVALID")),List.of());
        }
        var failures=new ArrayList<Failure>();var repairs=new ArrayList<Repair>();var schema=schema();
        if(!valid(in,schema,schema))return new Inspection(in,List.of(new Failure("","/","AI_SCHEMA_INVALID")),repairs);
        var seen=new HashSet<String>();var alternatives=new HashSet<String>();
        for(int i=0;i<in.path("facts").size();i++) {
            var f=in.path("facts").get(i);String id=f.path("factId").asText();String path="/facts/"+i;
            if(!f.path("medication").isNull()&&!f.path("kind").asText().equals("MEDICATION")) {
                ((ObjectNode)f).put("kind","MEDICATION");
                repairs.add(new Repair(id,"CATEGORY_FROM_MEDICATION_OBJECT",""));
            }
            if(id.isBlank()||!seen.add(id)||f.path("text").asText().isBlank())failures.add(new Failure(id,path,"AI_SCHEMA_INVALID"));
            var added=new ArrayList<String>();
            try {support((ObjectNode)f.path("support"),added,!f.path("task").isNull());}
            catch(AiProviderException e){failures.add(new Failure(id,path+"/support",e.code()));}
            added.forEach(ref->repairs.add(new Repair(id,"ADJACENT_QUESTION_CONTEXT",ref)));
            try {validateRelations(f,alternatives);}
            catch(AiProviderException e){failures.add(new Failure(id,path,e.code()));}
        }
        return new Inspection(in,List.copyOf(failures),List.copyOf(repairs));
    }
    private ArrayNode support(ObjectNode support,List<String> added,boolean actionRequest) {
        var ids=new LinkedHashSet<String>();
        for(String field:List.of("statementIds","questionIds","answerIds","correctionIds")) {
            var distinct=new HashSet<String>();
            for(var id:support.path(field)) {
                require(spans.containsKey(id.asText())&&distinct.add(id.asText()),"AI_EVIDENCE_INVALID");
                ids.add(id.asText());
            }
        }
        require(!ids.isEmpty(),"AI_EVIDENCE_INVALID");
        var ordered=new ArrayList<>(spans.values());
        for(String id:new ArrayList<>(ids)) {
            var span=spans.get(id);
            if(!bare(span))continue;
            int index=ordered.indexOf(span);
            var previous=index>0?ordered.get(index-1):null;
            boolean adjacent=previous!=null&&previous.source().sourceId().equals(span.source().sourceId())
                &&Objects.equals(previous.page(),span.page())&&!previous.speaker().equals(span.speaker())
                &&!previous.speaker().equals("UNKNOWN")&&!span.speaker().equals("UNKNOWN");
            if(adjacent&&endsQuestion(previous)) {
                if(ids.add(previous.id())) {((ArrayNode)support.get("questionIds")).add(previous.id());added.add(previous.id());}
            } else {
                // An acknowledgement can accompany an ALREADY selected, self-contained preceding instruction.
                // It cannot establish a finding on its own. Never discard the acknowledgement to hide failure.
                boolean acknowledgement=adjacent&&ids.contains(previous.id())&&!bare(previous)&&!question(previous);
                require(acknowledgement||ids.stream().anyMatch(ref->question(spans.get(ref))),"AI_ANSWER_CONTEXT_MISSING");
            }
        }
        // A clinician's request can have interrogative grammar. A question alone still cannot prove a finding.
        require(actionRequest&&ids.stream().anyMatch(ref->!bare(spans.get(ref))&&spans.get(ref).speaker().equals("CLINICIAN"))
            ||ids.stream().anyMatch(ref->!question(spans.get(ref))&&!bare(spans.get(ref)))
            ||ids.stream().anyMatch(ref->question(spans.get(ref)))&&ids.stream().anyMatch(ref->bare(spans.get(ref))),
            "AI_ANSWER_CONTEXT_MISSING");
        return json.valueToTree(ids);
    }
    private String body(UtteranceEvidence.Span s) {
        return s.quote().replaceFirst("^\\[\\d+\\]\\s*","").replaceFirst("^[^:]+:\\s*","").replaceAll("<[^>]*>","").trim();
    }
    private boolean bare(UtteranceEvidence.Span s) {
        return body(s).matches("(?i)(네|예|아니요|아뇨|응|yes|no|yep|nope|네,?\\s*없어요|아니요,?\\s*없어요)[.!?\\s]*");
    }
    private boolean question(UtteranceEvidence.Span s) {
        if(!endsQuestion(s))return false;
        // A turn can contain a finding followed by a different question; do not reject the whole turn.
        String[] sentences=body(s).split("(?<=[.!?。])\\s+");
        for(int i=0;i<sentences.length-1;i++) {
            String clause=sentences[i].trim();
            if(!clause.matches("(?s).*[?？]$")&&!clause.matches("(알겠습니다|그렇군요|네|예|좋습니다|없군요|없네요)[.!。]*"))
                return false;
        }
        return true;
    }
    private boolean endsQuestion(UtteranceEvidence.Span s) {
        return body(s).matches("(?s).*(?:[?？]|(?:나요|까요|습니까)[.\\s]*)$");
    }
    private void validateRelations(JsonNode f,Set<String> alternatives) {
        var condition=f.path("condition");boolean conditional=!condition.path("operator").asText().equals("NONE");
        require(conditional!=condition.path("clauses").isEmpty(),"AI_SEMANTIC_INVALID");
        for(var clause:condition.path("clauses"))require(!clause.asText().isBlank(),"AI_SCHEMA_INVALID");
        boolean med=f.path("kind").asText().equals("MEDICATION");
        boolean order=!f.path("medication").isNull();
        require(order==!f.path("medicationRole").asText().equals("NONE")&&(!order||med),"AI_SEMANTIC_INVALID");
        boolean future=f.path("actionStatus").asText().equals("FUTURE");
        require(med?f.path("task").isNull():future!=f.path("task").isNull(),"AI_SEMANTIC_INVALID");
        require(!f.path("medicationRole").asText().equals("CONDITIONAL")||conditional,"AI_CONDITION_MISSING");
        // This checks explicit internal contradictions, not general medical entailment.
        if(future||order&&!Set.of("HISTORICAL","EXAMPLE","SUPERSEDED").contains(f.path("medicationRole").asText())) {
            String words=f.path("text").asText()+" "+f.at("/medication/instructions").asText("");
            if(words.matches("(?s).*(?:으면|다면|경우에는|경우에만|할 때만).*"))
                require(conditional,"AI_CONDITION_MISSING");
        }
        if(!f.path("alternativeGroup").isNull()) {
            require(med&&f.path("medicationRole").asText().equals("ALTERNATIVE")&&alternatives.add(f.path("alternativeGroup").asText()),"AI_SEMANTIC_INVALID");
            require(f.at("/medication/name").isNull(),"AI_SEMANTIC_INVALID");
        }
        if(f.path("medicationRole").asText().equals("ALTERNATIVE"))require(!f.path("alternativeGroup").isNull(),"AI_SEMANTIC_INVALID");
        if(order) {
            var medication=f.path("medication");var times=new HashSet<String>();
            for(var time:medication.path("clockTimes")) {
                require(time.asText().matches("\\d{2}:\\d{2}")&&times.add(time.asText()),"AI_SEMANTIC_INVALID");
                try{LocalTime.parse(time.asText());}catch(RuntimeException invalid){throw new AiProviderException("AI_SEMANTIC_INVALID",false);}
            }
            require(times.isEmpty()==medication.path("scheduleRecurrence").isNull(),"AI_SEMANTIC_INVALID");
            if(Set.of("EXAMPLE","HISTORICAL","SUPERSEDED","STOP","ALTERNATIVE").contains(f.path("medicationRole").asText()))
                require(times.isEmpty(),"AI_SEMANTIC_INVALID");
        }
    }
    private ObjectNode claim(JsonNode f,ArrayNode ids) {
        var claim=json.createObjectNode();claim.set("utteranceIds",ids);claim.put("text",render(f));return claim;
    }
    private String render(JsonNode f) {
        String text=f.path("text").asText();var c=f.path("condition");
        if(c.path("operator").asText().equals("NONE"))return text;
        var clauses=new ArrayList<String>();c.path("clauses").forEach(v->clauses.add(v.asText()));
        String label=switch(c.path("operator").asText()){case "ALL"->"다음 조건 모두에 해당하면: ";case "ANY"->"다음 조건 중 하나라도 해당하면: ";default->"조건: ";};
        return label+String.join("; ",clauses)+" → "+text;
    }
    private ObjectNode item(JsonNode f,ArrayNode ids,String type) {
        var item=json.createObjectNode().put("itemKey",f.path("factId").asText()).put("itemType",type);
        item.set("utteranceIds",ids);var reasons=(ArrayNode)f.path("uncertaintyCodes").deepCopy();
        // Explicit experimental gate: no automatic TASK application before a separate promotion decision.
        if(!contains(reasons,"EXPERIMENTAL_SEMANTIC_REVIEW"))reasons.add("EXPERIMENTAL_SEMANTIC_REVIEW");
        if(f.path("medicationRole").asText().equals("ALTERNATIVE")&&!contains(reasons,"MEDICATION_CHOICE_REQUIRED"))reasons.add("MEDICATION_CHOICE_REQUIRED");
        if(f.path("medicationRole").asText().equals("STOP")&&!contains(reasons,"STOP_INSTRUCTION_REVIEW"))reasons.add("STOP_INSTRUCTION_REVIEW");
        item.set("uncertaintyCodes",reasons);
        item.put("isConditional",!f.at("/condition/operator").asText().equals("NONE")||Set.of("CONDITIONAL","ALTERNATIVE").contains(f.path("medicationRole").asText()));
        return item;
    }
    private ObjectNode medication(JsonNode f,ArrayNode ids) {
        var item=item(f,ids,"MEDICATION");var p=item.putObject("payload").put("schemaVersion",1);var m=f.path("medication");
        for(String key:List.of("name","doseText","frequencyText","startsOn","endsOn"))p.set(key,m.get(key));
        var instructions=new LinkedHashSet<String>();instructions.add(render(f));
        for(String key:List.of("courseDurationText","instructions"))if(!m.path(key).isNull()&&!m.path(key).asText().isBlank())instructions.add(m.path(key).asText());
        p.put("instructions",String.join(" / ",instructions));p.putNull("supersedesMedicationId");var plans=p.putArray("schedulePlans");
        for(var clock:m.path("clockTimes")) {
            var plan=plans.addObject();plan.set("recurrence",m.get("scheduleRecurrence"));plan.set("firstDate",m.get("startsOn"));
            plan.set("lastDate",m.path("scheduleRecurrence").asText().equals("ONCE")?m.get("startsOn"):m.get("endsOn"));
            plan.set("weekdays",m.get("weekdays"));plan.set("localTime",clock);
            plan.put("durationMinutes",30); // Existing service planning default; NEVER a clinician/course-duration fact.
        }
        return item;
    }
    private ObjectNode task(JsonNode f,ArrayNode ids) {
        var item=item(f,ids,"TASK");var p=item.putObject("payload").put("schemaVersion",1);var task=f.path("task");
        for(String key:List.of("kind","title","date","time","recurrence","weekdays"))p.set(key,task.get(key));
        p.set("lastDate",task.path("recurrence").asText().equals("ONCE")?task.get("date"):task.get("lastDate"));
        p.put("description",render(f));
        if(Set.of("HOSPITAL","EXAM").contains(task.path("kind").asText()))p.put("durationMinutes",120).put("durationSource","PLANNING_DEFAULT");
        else p.putNull("durationMinutes").putNull("durationSource");
        return item;
    }
    private boolean contains(ArrayNode nodes,String value){for(var n:nodes)if(n.asText().equals(value))return true;return false;}
    // Small validator for this fixed schema's supported subset; also validates saved/mock responses locally.
    private boolean valid(JsonNode value,JsonNode rule,JsonNode root) {
        if(rule.has("$ref"))return valid(value,root.at(rule.path("$ref").asText().substring(1)),root);
        if(rule.has("anyOf")){for(var choice:rule.get("anyOf"))if(valid(value,choice,root))return true;return false;}
        if(rule.has("const")&&!rule.get("const").equals(value))return false;
        if(rule.has("enum")){boolean found=false;for(var e:rule.get("enum"))if(e.equals(value))found=true;if(!found)return false;}
        if(rule.has("type")) {
            var type=rule.get("type");var types=type.isArray()?type:json.createArrayNode().add(type);
            boolean matches=false;for(var t:types)matches|=switch(t.asText()){case "null"->value.isNull();case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "object"->value.isObject();case "array"->value.isArray();default->false;};
            if(!matches)return false;
        }
        if(value.isObject()) {
            var properties=rule.path("properties");if(value.size()!=properties.size())return false;
            for(var entry:properties.properties())if(!value.has(entry.getKey())||!valid(value.get(entry.getKey()),entry.getValue(),root))return false;
        }
        if(value.isArray())for(var item:value)if(!valid(item,rule.path("items"),root))return false;
        if(value.isNumber()&&(rule.has("minimum")&&value.asDouble()<rule.path("minimum").asDouble()||rule.has("maximum")&&value.asDouble()>rule.path("maximum").asDouble()))return false;
        return true;
    }
    private static void require(boolean condition,String code){if(!condition)throw new AiProviderException(code,false);}
}
