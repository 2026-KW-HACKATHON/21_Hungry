package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** Local replay and claim/evidence export; explicitly not an automatic semantic judge. */
public final class EvidenceEvaluationReportMain {
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("v1.8-compare")){SemanticComparisonReportMain.main(args);return;}
        if(args.length>0&&args[0].equals("v1.8-semantics")){SemanticEvaluationReportMain.main(args);return;}
        var cases=new HashMap<String,EvalCase>();
        for(String set:List.of("synthetic","public-development","new-holdout"))for(var c:SummaryEvidenceEvaluationMain.campaignCases(set))cases.put(c.id(),c);
        boolean comparison=args.length>0&&args[0].equals(SummaryEvidenceEvaluationMain.V17_CAMPAIGN);
        if(args.length>0&&!comparison&&!args[0].equals("evidence-linking"))throw new IllegalArgumentException();
        var results=comparison?SummaryEvidenceEvaluationMain.V17_RESULTS:SummaryEvidenceEvaluationMain.RESULTS;var rows=JSON.createArrayNode();
        double total=0;int calls=0;long inputTokens=0,outputTokens=0,cached=0,reasoning=0;
        try(var paths=Files.walk(results,2)) {
            for(var file:paths.filter(p->p.getFileName().toString().equals("record.json")).sorted().toList()) {
                var record=JSON.readTree(read(file));
                if(comparison&&(!Set.of("1.6","1.7").contains(record.path("promptVersion").asText())||record.path("set").asText().equals("semantic-regression")))continue;
                var row=JSON.createObjectNode();rows.add(row);calls++;
                row.set("record",record);total+=record.path("chargedUsd").asDouble();
                var c=cases.get(record.path("caseId").asText());
                if(c==null||!hash(canonical(JSON.valueToTree(c.input()))).equals(record.path("inputSha256").asText()))throw new IllegalStateException("Input changed since evaluation");
                Path responsePath=file.getParent().resolve("response.json");
                if(!Files.exists(responsePath)){row.put("parserReplay","NO_SAVED_RESPONSE");continue;}
                var response=JSON.readTree(read(responsePath));var usage=response.path("usage");
                if(!record.path("reused").asBoolean()) {
                    inputTokens+=usage.path("input_tokens").asLong();outputTokens+=usage.path("output_tokens").asLong();
                    cached+=usage.path("input_tokens_details").path("cached_tokens").asLong();reasoning+=usage.path("output_tokens_details").path("reasoning_tokens").asLong();
                }
                String version=record.path("promptVersion").asText();
                var adapter=new OpenAiProcessingAdapter(JSON,new AnalysisOutputValidator(JSON),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),Set.of("1.6","1.7").contains(version)?UtteranceEvidence.GROUNDED_PROTOCOL:UtteranceEvidence.PROTOCOL);
                try{adapter.parseAnalysis(response,c.input(),version);row.put("parserReplay","ACCEPTED");}
                catch(AiProviderException e){row.put("parserReplay",e.code());}
                var raw=JSON.readTree(SavedEvidenceAuditMain.output(response));
                var numericIssues=row.putArray("contractIssues");
                for(int i=0;i<raw.path("items").size();i++) {
                    var payload=raw.path("items").get(i).path("payload");
                    if(payload.path("durationMinutes").isNumber()&&(payload.path("durationMinutes").asLong()<1||payload.path("durationMinutes").asLong()>1440))numericIssues.add("/items/"+i+"/payload/durationMinutes out of 1..1440");
                    for(int j=0;j<payload.path("schedulePlans").size();j++) {
                        var n=payload.path("schedulePlans").get(j).path("durationMinutes");
                        if(!n.isIntegralNumber()||n.asLong()<1||n.asLong()>1440)numericIssues.add("/items/"+i+"/payload/schedulePlans/"+j+"/durationMinutes out of 1..1440");
                    }
                }
                var catalog=new UtteranceEvidence(JSON,c.input());var byId=new HashMap<String,UtteranceEvidence.Span>();catalog.spans().forEach(s->byId.put(s.id(),s));
                var claims=row.putArray("claimEvidencePairs");
                for(int i=0;i<raw.path("summary").size();i++)pair(claims,"/summary/"+i,raw.path("summary").get(i),byId);
                for(String field:UtteranceEvidence.DETAIL_FIELDS)for(int i=0;i<raw.path("details").path(field).size();i++)pair(claims,"/details/"+field+"/"+i,raw.path("details").path(field).get(i),byId);
                for(int i=0;i<raw.path("items").size();i++)pair(claims,"/items/"+i,raw.path("items").get(i),byId);
            }
        }
        long reused=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("record").path("reused").asBoolean()).count();
        var report=JSON.createObjectNode().put("records",calls).put("calls",calls-reused).put("reused",reused).put("chargedUsd",total).put("additionalBudgetUsd",comparison?3.0:1.0)
            .put("inputTokens",inputTokens).put("outputTokens",outputTokens).put("cachedInputTokens",cached).put("reasoningTokens",reasoning)
            .put("meaningAccuracy","NOT automatically graded; see semantic-review.json. Existing IDs are not proof of entailment.");report.set("results",rows);
        save(results.resolve("format-audit.json"),report);
        System.out.printf(Locale.ROOT,"Local replay/export: %d records (%d calls, %d reused), charged=$%.8f; semantic approval NOT inferred.%n",calls,calls-reused,reused,total);
    }
    static void pair(ArrayNode rows,String path,JsonNode claim,Map<String,UtteranceEvidence.Span> spans) {
        var row=rows.addObject().put("path",path);row.set("claim",claim);var refs=row.putArray("sources");
        for(var id:claim.path("utteranceIds")) {
            var span=spans.get(id.asText());
            if(span==null){refs.addObject().put("id",id.asText()).put("error","UNKNOWN_ID");continue;}
            refs.addObject().put("id",span.id()).put("speaker",span.speaker()).put("sourceId",span.source().sourceId()).put("textVersion",span.source().textVersion())
                .put("quote",span.quote()).put("start",span.source().text().codePointCount(0,span.charStart())).put("end",span.source().text().codePointCount(0,span.charEnd()));
        }
    }
}
