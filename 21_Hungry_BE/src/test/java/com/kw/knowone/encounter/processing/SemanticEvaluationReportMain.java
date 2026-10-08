package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** Offline saved-response replay. Structural rejection and meaning review are separate. No network/DB. */
public final class SemanticEvaluationReportMain {
    public static void main(String[] args) throws Exception {
        var cases=new HashMap<String,EvalCase>();
        for(String set:List.of("synthetic","reviewed-development","semantic-regression","v18-holdout"))
            for(var c:SummaryEvidenceEvaluationMain.campaignCases(set))cases.put(c.id(),c);
        var root=JSON.createObjectNode().put("reviewStatus","STRUCTURAL_REPLAY_ONLY_NOT_SEMANTIC_APPROVAL");
        var rows=root.putArray("results");var groups=root.putObject("groups");
        double total=0;int calls=0,reused=0;long input=0,output=0,cache=0,reasoning=0,latency=0;
        try(var files=Files.walk(SummaryEvidenceEvaluationMain.V17_RESULTS,2)) {
            for(var file:files.filter(p->p.getFileName().toString().equals("record.json")).sorted().toList()) {
                var rec=JSON.readTree(read(file));
                if(rec.path("promptVersion").asText().equals("1.8.1"))continue; // Independent, versioned comparison report.
                var c=cases.get(rec.path("caseId").asText());
                if(c==null||!hash(canonical(JSON.valueToTree(c.input()))).equals(rec.path("inputSha256").asText()))throw new IllegalStateException("Saved input changed");
                var row=rows.addObject();row.set("record",rec);total+=rec.path("chargedUsd").asDouble();
                if(rec.path("reused").asBoolean())reused++;else calls++;
                String version=rec.path("promptVersion").asText(),model=rec.path("model").asText(MODEL);
                String groupKey=version+"|"+model+"|"+rec.path("set").asText();
                var group=(ObjectNode)groups.get(groupKey);if(group==null){group=groups.putObject(groupKey);group.put("records",0).put("accepted",0).put("failed",0).put("chargedUsd",0);}
                group.put("records",group.path("records").asInt()+1).put("chargedUsd",group.path("chargedUsd").asDouble()+rec.path("chargedUsd").asDouble());
                var responsePath=file.getParent().resolve("response.json");
                if(!Files.exists(responsePath)){row.put("parserReplay","NO_SAVED_RESPONSE");row.put("failureCategory","COMMUNICATION");group.put("failed",group.path("failed").asInt()+1);continue;}
                var response=JSON.readTree(read(responsePath));var usage=response.path("usage");row.set("usage",usage);
                if(!rec.path("reused").asBoolean()) {
                    input+=usage.path("input_tokens").asLong();output+=usage.path("output_tokens").asLong();
                    cache+=usage.at("/input_tokens_details/cached_tokens").asLong();reasoning+=usage.at("/output_tokens_details/reasoning_tokens").asLong();latency+=rec.path("elapsedMillis").asLong();
                }
                String protocol=version.equals("1.8")?SemanticAnalysis.PROTOCOL:UtteranceEvidence.GROUNDED_PROTOCOL;
                var adapter=new OpenAiProcessingAdapter(JSON,new AnalysisOutputValidator(JSON),"http://localhost:1","","unused","unused",model,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),protocol,version);
                try {
                    var result=adapter.parseAnalysis(response,c.input(),version);row.put("parserReplay","ACCEPTED");row.set("resolved",JSON.valueToTree(result));group.put("accepted",group.path("accepted").asInt()+1);
                } catch(AiProviderException e){row.put("parserReplay",e.code());group.put("failed",group.path("failed").asInt()+1);}
                JsonNode raw;
                try {raw=JSON.readTree(SavedEvidenceAuditMain.output(response));}
                catch(RuntimeException e){row.put("failureCategory","JSON_GENERATION");continue;}
                row.set("rawAnalysis",raw);row.put("jsonValid",true);
                if(!response.path("status").asText().equals("completed"))row.put("failureCategory","INCOMPLETE_OUTPUT");
                else if(!row.path("parserReplay").asText().equals("ACCEPTED"))row.put("failureCategory",switch(row.path("parserReplay").asText()){case "AI_EVIDENCE_INVALID"->"EVIDENCE_VALIDATION";case "AI_SEMANTIC_INVALID"->"STRUCTURAL_RELATION";default->"SCHEMA_OR_CONTRACT";});
                var catalog=new UtteranceEvidence(JSON,c.input());var spans=new HashMap<String,UtteranceEvidence.Span>();catalog.spans().forEach(s->spans.put(s.id(),s));
                var pairs=row.putArray("claimEvidencePairs");
                if(version.equals("1.8")) {
                    var failures=row.putArray("isolatedFactFailures");
                    for(int i=0;i<raw.path("facts").size();i++) {
                        var fact=raw.path("facts").get(i);var claim=(ObjectNode)fact.deepCopy();var refs=claim.putArray("utteranceIds");var ids=new LinkedHashSet<String>();
                        for(String field:List.of("statementIds","questionIds","answerIds","correctionIds"))fact.path("support").path(field).forEach(id->ids.add(id.asText()));ids.forEach(refs::add);
                        EvidenceEvaluationReportMain.pair(pairs,"/facts/"+i,claim,spans);
                        var one=JSON.createObjectNode().put("schemaVersion","1.8");one.putArray("facts").add(fact);one.putArray("summaryFactIds").add(fact.path("factId").asText());
                        try {new AnalysisOutputValidator(JSON).validate(new SemanticAnalysis(JSON,catalog).resolve(one.toString()),c.input(),"OPENAI",model,version,0,0);}
                        catch(AiProviderException e){failures.addObject().put("path","/facts/"+i).put("factId",fact.path("factId").asText()).put("code",e.code());}
                    }
                } else {
                    for(int i=0;i<raw.path("summary").size();i++)EvidenceEvaluationReportMain.pair(pairs,"/summary/"+i,raw.path("summary").get(i),spans);
                    for(String field:UtteranceEvidence.DETAIL_FIELDS)for(int i=0;i<raw.path("details").path(field).size();i++)EvidenceEvaluationReportMain.pair(pairs,"/details/"+field+"/"+i,raw.path("details").path(field).get(i),spans);
                    for(int i=0;i<raw.path("items").size();i++)EvidenceEvaluationReportMain.pair(pairs,"/items/"+i,raw.path("items").get(i),spans);
                }
            }
        }
        root.put("records",rows.size()).put("calls",calls).put("reused",reused).put("chargedUsd",total).put("budgetUsd",3)
            .put("inputTokens",input).put("outputTokens",output).put("cachedInputTokens",cache).put("reasoningTokens",reasoning).put("elapsedMs",latency);
        save(SummaryEvidenceEvaluationMain.V17_RESULTS.resolve("v1.8-format-audit.json"),root);
        System.out.printf(Locale.ROOT,"Offline replay: %d records, %d calls, %d reused, charged=$%.8f. NOT semantic approval.%n",rows.size(),calls,reused,total);
    }
}
