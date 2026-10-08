package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;
import static com.kw.knowone.encounter.processing.SemanticComparisonMain.*;

/** Offline reproducible format replay + explicit AI/source review, NOT an automatic clinical judge. */
public final class SemanticComparisonReportMain {
    public static void main(String[] args) throws Exception {
        freeze();
        var inputs=new HashMap<String,EvalCase>();
        for(var c:original())inputs.put(c.id(),c);
        for(var c:fresh())inputs.put(c.id(),c);
        var reviews=new HashMap<String,JsonNode>();
        for(var r:JSON.readTree(read(ROOT.resolve("v1.8.1-manual-review.json"))).path("rows")) {
            String key=r.path("candidate").asText()+"|"+r.path("caseId").asText();
            if(reviews.putIfAbsent(key,r)!=null)throw new IllegalStateException("Duplicate review");
        }
        var report=JSON.createObjectNode().put("promotion","BLOCKED")
            .put("reviewStatus","AI source comparison; not clinician validated")
            .put("parserRevision","r2").put("budgetUsd",3.0).put("initialChargedUsd",1.11464775)
            .put("ledgerChargedUsd",charged(ROOT));
        var rows=report.putArray("rows");var groups=report.putObject("groups");
        try(var files=Files.walk(ROOT,2)) {
            for(var file:files.filter(p->p.getFileName().toString().equals("record.json")).sorted().toList()) {
                var rec=JSON.readTree(read(file));if(!rec.path("promptVersion").asText().equals(VERSION))continue;
                String id=rec.path("caseId").asText(),candidate=rec.path("candidate").asText();
                var c=inputs.get(id);if(c==null)throw new IllegalStateException("Unknown case");
                if(!rec.path("inputSha256").asText().equals(hash(canonical(JSON.valueToTree(c.input())))))throw new IllegalStateException("Input changed");
                verifyCachedRequest(file.getParent(),canonical(JSON.valueToTree(request(c,candidate,""))));
                var row=rows.addObject();row.set("record",rec);
                boolean isFresh=rec.path("set").asText().equals("fresh");
                row.put("sourceClass",id.startsWith("day")?"PUBLIC_MOCK":"KOREAN_SYNTHETIC");
                row.put("set",isFresh?"fresh4":"original20");
                row.put("formatPass",false).put("jsonValid",false).put("failureCategory","COMMUNICATION");
                var responsePath=file.getParent().resolve("response.json");
                if(Files.exists(responsePath)) {
                    var response=JSON.readTree(read(responsePath));
                    if(!response.path("status").asText().equals("completed")||response.at("/usage/output_tokens").asInt()>=OUTPUT_LIMIT)
                        row.put("failureCategory","OUTPUT_TRUNCATED");
                    else {
                        var inspection=new SemanticAnalysisV2(JSON,new UtteranceEvidence(JSON,c.input())).inspect(SavedEvidenceAuditMain.output(response));
                        row.set("inspection",JSON.valueToTree(inspection));
                        row.put("jsonValid",!inspection.normalized().isNull());
                        row.put("rawFactCount",inspection.normalized().path("facts").size());
                        int candidates=0;
                        for(var fact:inspection.normalized().path("facts")) {
                            if(!fact.path("task").isNull()||!fact.path("medication").isNull()&&!Set.of("HISTORICAL","EXAMPLE","SUPERSEDED").contains(fact.path("medicationRole").asText()))candidates++;
                        }
                        row.put("rawCandidateCount",candidates);
                        try {
                            var parsed=adapter(rec.path("model").asText(),"").parseAnalysis(response,c.input(),VERSION);
                            row.put("formatPass",true).put("failureCategory","NONE");row.set("resolved",JSON.valueToTree(parsed));
                        }catch(AiProviderException e){
                            row.put("errorCode",e.code()).put("failureCategory",switch(e.code()) {
                                case "AI_JSON_INVALID"->"JSON";
                                case "AI_SCHEMA_INVALID"->"SCHEMA";
                                case "AI_EVIDENCE_INVALID"->"EVIDENCE_POSITION";
                                default->"INTERNAL_RELATION";
                            });
                        }
                    }
                }
                var review=reviews.get(candidate+"|"+id);
                row.put("reviewed",review!=null&&review.path("reviewed").asBoolean());
                if(review!=null)row.set("review",review);
                boolean semanticPass=row.path("reviewed").asBoolean();
                if(review!=null)for(var issue:review.path("issues"))if(!issue.path("severity").asText().equals("MINOR"))semanticPass=false;
                row.put("observedMeaningPass",semanticPass).put("overallPass",semanticPass&&row.path("formatPass").asBoolean());
                double pipeline=rec.path("chargedUsd").asDouble();long latency=rec.path("elapsedMillis").asLong();
                if(candidate.equals("C")) {
                    var parent=JSON.readTree(read(ROOT.resolve(rec.path("parentRequestSha256").asText()).resolve("record.json")));
                    pipeline+=parent.path("chargedUsd").asDouble();latency+=parent.path("elapsedMillis").asLong();
                }
                row.put("pipelineUsd",pipeline).put("pipelineElapsedMillis",latency);
                List<String> sets=new ArrayList<>(List.of(isFresh?"fresh4":"original20"));
                if(!isFresh&&SCREEN.contains(id))sets.add("screen6");
                for(String set:sets)for(String source:List.of("ALL",row.path("sourceClass").asText()))aggregate(groups,set+"|"+candidate+"|"+source,row);
            }
        }
        report.put("newCalls",rows.size());
        double newCost=0;for(var row:rows)newCost+=row.at("/record/chargedUsd").asDouble();
        report.put("newChargedUsd",newCost).put("remainingUsd",3-charged(ROOT));
        for(var entry:groups.properties()) {
            var g=(ObjectNode)entry.getValue();int n=g.path("cases").asInt();
            g.put("meanElapsedMillis",g.path("elapsedMillis").asLong()/(double)n)
                .put("meanPipelineElapsedMillis",g.path("pipelineElapsedMillis").asLong()/(double)n);
        }
        save(ROOT.resolve("v1.8.1-comparison.json"),report);
        System.out.printf(Locale.ROOT,"Offline: %d new calls, $%.8f new; ledger $%.8f. Promotion BLOCKED; raw/format/meaning separate.%n",rows.size(),newCost,charged(ROOT));
    }
    static void aggregate(ObjectNode groups,String key,JsonNode row) {
        var g=(ObjectNode)groups.get(key);if(g==null)g=groups.putObject(key);
        add(g,"cases",1);for(String flag:List.of("formatPass","jsonValid","reviewed","observedMeaningPass","overallPass"))if(row.path(flag).asBoolean())add(g,flag,1);
        add(g,"rawFactCount",row.path("rawFactCount").asLong());add(g,"rawCandidateCount",row.path("rawCandidateCount").asLong());
        var rec=row.path("record");add(g,"elapsedMillis",rec.path("elapsedMillis").asLong());add(g,"pipelineElapsedMillis",row.path("pipelineElapsedMillis").asLong());
        for(String field:List.of("input_tokens","output_tokens"))add(g,field,rec.path("usage").path(field).asLong());
        add(g,"cachedTokens",rec.at("/usage/input_tokens_details/cached_tokens").asLong());
        add(g,"cacheWriteTokens",rec.at("/usage/input_tokens_details/cache_write_tokens").asLong());
        add(g,"reasoningTokens",rec.at("/usage/output_tokens_details/reasoning_tokens").asLong());add(g,"retries",rec.path("retries").asLong());
        g.put("incrementalUsd",g.path("incrementalUsd").asDouble()+rec.path("chargedUsd").asDouble());
        g.put("pipelineUsd",g.path("pipelineUsd").asDouble()+row.path("pipelineUsd").asDouble());
        var units=object(g,"atomicErrors");var severities=object(g,"severities");var caseTypes=new HashSet<String>();
        for(var issue:row.at("/review/issues")) {
            String type=issue.path("category").asText();add(units,type,1);add(severities,issue.path("severity").asText(),1);caseTypes.add(type);
        }
        for(String type:caseTypes)add(object(g,"casesWithError"),type,1);
        add(object(g,"formatCategories"),row.path("failureCategory").asText(),1);
    }
    static ObjectNode object(ObjectNode node,String key){return node.has(key)?(ObjectNode)node.get(key):node.putObject(key);}
    static void add(ObjectNode node,String key,long n){node.put(key,node.path(key).asLong()+n);}
}
