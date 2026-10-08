package com.kw.knowone.encounter.processing;

import com.kw.knowone.encounter.processing.AiProcessingPort.*;
import java.nio.file.*;
import java.time.*;
import java.net.http.HttpClient;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** Replays immutable public/mock responses through the real parser. Never calls an API. */
public final class SavedEvidenceAuditMain {
    static final Path REPORT=Path.of("eval-results/evidence-linking-2026-10-09");
    public static void main(String[] args) throws Exception {
        Files.createDirectories(REPORT);
        var rows=JSON.createArrayNode();
        var adapter=new OpenAiProcessingAdapter(JSON,new AnalysisOutputValidator(JSON),"http://localhost:1","",
            "unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient());
        try(var paths=Files.walk(RESULTS,2)) {
            for(var path:paths.filter(p->p.getFileName().toString().equals("record.json")).sorted().toList()) {
                var record=JSON.readTree(read(path));
                if(!Set.of("1.1","1.3").contains(record.path("promptVersion").asText()))continue;
                var row=JSON.createObjectNode();rows.add(row);
                row.put("requestSha256",path.getParent().getFileName().toString());
                for(String key:List.of("caseId","set","promptVersion","requestLayout","status","errorCode","inputSha256"))row.set(key,record.path(key));
                var issues=JSON.createArrayNode();row.set("issues",issues);
                Path responsePath=path.getParent().resolve("response.json");
                if(!Files.exists(responsePath)){row.put("classification","TRANSPORT_OR_NO_SAVED_RESPONSE");continue;}
                var response=JSON.readTree(read(responsePath));
                row.put("providerStatus",response.path("status").asText());row.set("usage",response.path("usage"));
                var input=inputFromRequest(JSON.readTree(read(path.getParent().resolve("request.json"))));
                String raw=output(response);
                JsonNode value;
                try {value=JSON.readTree(raw);}catch(RuntimeException e){row.put("classification","JSON_SYNTAX");continue;}
                row.put("hasSummary",!value.path("summary").asText("").isBlank());
                if("incomplete".equals(response.path("status").asText())||response.path("usage").path("output_tokens").asInt()>=OUTPUT_LIMIT)row.put("classification","TRUNCATION");
                evidenceIssues(value.path("evidence"),"/evidence",input,issues);
                value.path("details").properties().forEach(entry->{int n=0;for(var detail:entry.getValue()) {
                    int j=0;var seen=new HashSet<Integer>();
                    for(var index:detail.path("evidenceIndexes")) {
                        if(!index.isIntegralNumber()||index.asInt()<0||index.asInt()>=value.path("evidence").size()||!seen.add(index.asInt()))
                            issue(issues,"/details/"+entry.getKey()+"/"+n+"/evidenceIndexes/"+j,"INDEX_INVALID",index.asText()+"; evidenceCount="+value.path("evidence").size());
                        j++;
                    }n++;
                }});
                for(int i=0;i<value.path("items").size();i++)evidenceIssues(value.path("items").get(i).path("evidence"),"/items/"+i+"/evidence",input,issues);
                try {
                    var parsed=adapter.parseAnalysis(response,input,record.path("promptVersion").asText());
                    row.put("replayedStatus","ACCEPTED");
                    row.put("classification",issues.isEmpty()?"FORMAT_ACCEPTED_NOT_SEMANTICALLY_VERIFIED":"ITEM_EVIDENCE_DOWNGRADED_BY_LEGACY_VALIDATOR");
                    row.set("itemReviewReasons",JSON.valueToTree(parsed.items().stream().map(AnalysisItem::reviewReasons).toList()));
                }catch(AiProviderException e){
                    row.put("replayedStatus",e.code());
                    if(!row.has("classification"))row.put("classification",switch(e.code()) {
                        case "AI_EVIDENCE_INVALID" -> "EVIDENCE_VALIDATION";
                        case "AI_SCHEMA_INVALID" -> "SCHEMA_MISMATCH";
                        case "AI_OUTPUT_INCOMPLETE" -> "TRUNCATION";
                        default -> "PROVIDER_OUTPUT";
                    });
                }
            }
        }
        save(REPORT.resolve("saved-response-audit.json"),rows);
        var md=new StringBuilder("# 저장 응답 재검사 (네트워크 호출 없음)\n\n실제 parseAnalysis/AnalysisOutputValidator 재실행. JSON 문법 검사와 전체 근거 진단을 추가했으며 원본 응답을 고치지 않았다. 경로는 모델 출력 JSON Pointer. 요약이 있어도 root 근거 또는 details 인덱스 하나가 잘못되면 전체가 실패한다.\n\n| 사례 | 버전 / 배열 순서 | 요청 해시 | 분류 | 정확한 실패 경로와 원인 | 요약 존재 |\n|---|---|---|---|---|---|\n");
        for(var row:rows)if("FAILED".equals(row.path("status").asText())) {
            var failures=new ArrayList<String>();for(var issue:row.path("issues"))failures.add(issue.path("path").asText()+": "+issue.path("reason").asText()+" ("+issue.path("detail").asText()+")");
            md.append("| ").append(row.path("caseId").asText()).append(" | ").append(row.path("promptVersion").asText()).append(" / ").append(row.path("requestLayout").asText("original"))
              .append(" | ").append(row.path("requestSha256").asText().substring(0,12)).append(" | ").append(row.path("classification").asText()).append(" | ")
              .append(String.join("<br>",failures)).append(" | ").append(row.path("hasSummary").asBoolean()).append(" |\n");
        }
        Files.writeString(REPORT.resolve("saved-response-audit.md"),md.toString());
        System.out.println("Saved response replay complete: "+rows.size()+" records; full diagnostics saved (no medical text in log).");
    }
    static String output(JsonNode response) {for(var entry:response.path("output"))for(var part:entry.path("content"))if("output_text".equals(part.path("type").asText()))return part.path("text").asText();return "";}
    static AnalysisInput inputFromRequest(JsonNode request) {
        String raw=request.at("/input/1/content/0/text").asText();var c=JSON.readTree(raw.substring(raw.indexOf('\n')+1));
        var sources=new ArrayList<SourceText>();for(var s:c.path("sources"))sources.add(new SourceText(s.path("sourceId").asText(),s.path("textVersion").asInt(),s.path("sourceType").asText(),s.path("mediaType").asText(),s.path("text").asText()));
        return new AnalysisInput(LocalDate.parse(c.path("occurredOn").asText()),Instant.parse(c.path("analyzedAt").asText()),c.path("timezone").asText(),sources,List.of());
    }
    static void evidenceIssues(JsonNode list,String pointer,AnalysisInput input,ArrayNode issues) {
        for(int i=0;i<list.size();i++) {
            var e=list.get(i);String p=pointer+"/"+i;String quote=e.path("quote").asText();
            var source=input.sources().stream().filter(s->s.sourceId().equals(e.path("sourceId").asText())).findFirst().orElse(null);
            if(source==null){issue(issues,p+"/sourceId","UNKNOWN_SOURCE_ID","not in request.sources");continue;}
            if(source.textVersion()!=e.path("textVersion").asInt()){issue(issues,p+"/textVersion","STALE_VERSION","version mismatch");continue;}
            String text=source.text();int page=e.path("page").asInt(0);
            if(!e.path("page").isNull()) {String[] pages=text.split("\\f",-1);if(page<1||page>pages.length||!source.sourceType().equals("DOCUMENT")){issue(issues,p+"/page","PAGE_INVALID","page/source mismatch");continue;}text=pages[page-1];}
            int from=0,found=-1,occ=e.path("occurrenceIndex").asInt(-1);
            for(int n=0;n<=occ&&!quote.isEmpty();n++){found=text.indexOf(quote,from);if(found<0)break;from=found+quote.length();}
            if(found<0) {
                String reason="QUOTE_NOT_CONTIGUOUS";
                if(!quote.isEmpty()&&text.replace("<UNIN/>","").replace("<UNSURE>","").replaceAll(" +"," ").contains(quote))reason="TRANSCRIPTION_TAG_REMOVED";
                if(!quote.isEmpty()&&quote.startsWith("의사: ")&&text.contains(quote.substring(4)))reason="SPEAKER_PREFIX_FABRICATED";
                issue(issues,p+"/quote",reason,"exact nth substring lookup failed; occurrence="+occ);
            }
        }
    }
    static void issue(ArrayNode issues,String path,String reason,String detail){issues.addObject().put("path",path).put("reason",reason).put("detail",detail);}
}
