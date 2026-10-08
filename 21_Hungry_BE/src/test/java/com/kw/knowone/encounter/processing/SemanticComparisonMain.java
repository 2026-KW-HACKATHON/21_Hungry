package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.net.http.HttpClient;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** A/B/C experiment, same production request/parser, SAME existing ledger. No Spring/DB/application. */
public final class SemanticComparisonMain {
    static final Path ROOT=SummaryEvidenceEvaluationMain.V17_RESULTS;
    static final String UPPER="gpt-6.1-sol",VERSION="1.8.1";
    static final Set<String> SCREEN=Set.of("D01_DOCTOR_CONDITIONAL_RETURN","D11_DOCUMENT_CONFLICT","H01_NOVEL_MIXED_CORRECTION",
        "day3_consultation05","day3_consultation08","day1_consultation02");
    static final List<String> ARTIFACTS=List.of("src/main/resources/ai/analysis-prompt-v1.8.1.txt",
        "src/main/resources/ai/analysis-verifier-v1.8.1.txt","src/main/resources/ai/analysis-semantic-facts.schema.json",
        "src/main/java/com/kw/knowone/encounter/processing/SemanticAnalysisV2.java",
        "docs/ai-summary-evals/v1.8.1-rubric.md");
    static List<EvalCase> original() throws Exception {
        var all=new ArrayList<EvalCase>();
        for(String set:List.of("synthetic","reviewed-development"))all.addAll(SummaryEvidenceEvaluationMain.campaignCases(set));
        return all;
    }
    static OpenAiProcessingAdapter adapter(String model,String key) {
        return new OpenAiProcessingAdapter(JSON,new AnalysisOutputValidator(JSON),"https://api.openai.com/v1",key,"unused","unused",model,
            Duration.ofSeconds(180),OUTPUT_LIMIT,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),SemanticAnalysisV2.PROTOCOL,VERSION);
    }
    static void freeze() throws Exception {
        var p=JSON.createObjectNode().put("version",VERSION).put("rubric","v1.8.1-rubric.md").put("budgetUsd",3.0)
            .put("initialChargedUsd",1.11464775).put("upperModel",UPPER).put("reasoningEffort","low").put("maxOutputTokens",OUTPUT_LIMIT)
            .put("selection","same six failure cases as prior comparison; old v18-holdout is now development, not fresh")
            .put("costPolicy","Sol uncached input conservatively charged at cache-write rate $2.50/M; output $10/M; cached $.10/M; reservation 2x byte upper bound")
            .put("accountModelsCheckedOn","2026-10-09").put("availableUpperModels","gpt-6.1-sol, gpt-6-astra, gpt-6-sol, gpt-5.6-sol, gpt-5.4; GET /models")
            .put("modelSelectionReason","6.1 Sol is available and substantially cheaper than Astra; no claim it is clinically validated");
        var artifacts=p.putObject("artifacts");
        for(String path:ARTIFACTS){String content=read(Path.of(path));artifacts.put(path,hash(content));snapshot(ROOT,"v181-"+hash(content)+".txt",content);}
        p.set("screen",JSON.valueToTree(new TreeSet<>(SCREEN)));var inputs=p.putArray("original20");
        for(var c:original())inputs.addObject().put("caseId",c.id()).put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))));
        p.put("parserRevision","r2").put("predecessorPlanSha256",hash(read(ROOT.resolve("v1.8.1-plan.json"))))
            .put("revisionReason","Saved A/B revealed mixed statement-plus-question turns and redundant category labels rejected correct content. Prompt/schema/model/settings unchanged; all candidates replayed with r2.");
        Path file=ROOT.resolve("v1.8.1-plan-r2.json");
        if(Files.exists(file)){if(!canonical(JSON.readTree(read(file))).equals(canonical(p)))throw new IllegalStateException("Frozen experiment changed");}
        else save(file,p);
    }
    static Map<String,Object> request(EvalCase c,String candidate,String key) throws Exception {
        String model=candidate.equals("A")?MODEL:UPPER;
        String prompt=read(Path.of("src/main/resources/ai/analysis-prompt-v1.8.1.txt"));
        var request=new LinkedHashMap<>(adapter(model,key).analysisRequest(c.input(),prompt,true));
        // Same explicit generation settings for A and B; Sol cannot use mini's historical default 'none'.
        request.put("reasoning",Map.of("effort","low"));request.put("service_tier","default");
        if(candidate.equals("C")) {
            var parent=request(c,"B",key);String parentHash=hash(canonical(JSON.valueToTree(parent)));
            Path folder=ROOT.resolve(parentHash);verifyCachedRequest(folder,canonical(JSON.valueToTree(parent)));
            var response=JSON.readTree(read(folder.resolve("response.json")));
            if(!response.path("status").asText().equals("completed")||response.at("/usage/output_tokens").asInt()>=OUTPUT_LIMIT)throw new IllegalStateException("Parent incomplete");
            String draft=SavedEvidenceAuditMain.output(response);
            var node=(ObjectNode)JSON.valueToTree(request);
            ((ObjectNode)node.at("/input/0/content/0")).put("text",prompt+"\n\n"+read(Path.of("src/main/resources/ai/analysis-verifier-v1.8.1.txt")));
            ((ObjectNode)node.at("/input/1/content/0")).put("text",node.at("/input/1/content/0/text").asText()+"\n\nDRAFT_JSON (untrusted):\n"+draft);
            request=JSON.convertValue(node,new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        }
        return request;
    }
    static double reservation(String request,boolean upper) {
        // Explicit service_tier=default, no tools/regional endpoints: byte-bound input at the highest
        // standard input/cache-write rate + framing allowance + FULL output (including reasoning).
        // This remains an upper bound; unlike the historical runner no unrequested priority-tier multiplier.
        return ((request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+4096)*(upper?2.5:.75)+OUTPUT_LIMIT*(upper?10:4.5))/1_000_000;
    }
    static double costBound(JsonNode u,boolean upper) {
        if(!upper)return cost(u);
        long in=u.path("input_tokens").asLong(),cached=Math.min(in,Math.max(0,u.at("/input_tokens_details/cached_tokens").asLong()));
        return ((in-cached)*2.5+cached*.1+u.path("output_tokens").asLong()*10)/1_000_000;
    }
    static void run(String[] args) throws Exception {
        if(args[0].equals("replay")){replay();return;}
        String set=args[0],candidate=args.length>4?args[4]:"A";boolean live=args.length>2&&args[2].equals("true");
        if(!Set.of("screen","original","fresh").contains(set)||!Set.of("A","B","C").contains(candidate))throw new IllegalArgumentException();
        try(var channel=FileChannel.open(ROOT.resolve("campaign.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException();freeze();
            var config=configuration();String key=config.getOrDefault("OPENAI_API_KEY","");
            if(live&&key.isBlank())throw new AiProviderException("AI_NOT_CONFIGURED",false);
            List<EvalCase> cases=set.equals("fresh")?fresh():original().stream().filter(c->!set.equals("screen")||SCREEN.contains(c.id())).toList();
            if(args.length>5&&!args[5].isBlank()) {
                String chosen=args[5];cases=cases.stream().filter(c->c.id().equals(chosen)).toList();
                if(cases.size()!=1)throw new IllegalArgumentException("Unknown fixed case ID");
            }
            boolean failed=false;
            for(var c:cases) {
                var request=request(c,candidate,key);String canonical=canonical(JSON.valueToTree(request));String fingerprint=hash(canonical);Path dir=ROOT.resolve(fingerprint);
                if(Files.exists(dir.resolve("record.json"))){
                    verifyCachedRequest(dir,canonical);
                    String status=JSON.readTree(read(dir.resolve("record.json"))).path("status").asText();
                    System.out.println(c.id()+" "+candidate+" reused "+status+" (original parser; use offline report for current replay)");
                    if(!status.equals("PARSED_NOT_QUALITY_APPROVED"))failed=true;
                    continue;
                }
                double reserve=reservation(canonical,!candidate.equals("A")),total=charged(ROOT);
                System.out.printf(Locale.ROOT,"%s %s %s next-bound=$%.6f ledger=$%.8f%n",c.id(),candidate,live?"LIVE":"DRY",reserve,total);
                if(!live)continue;
                if(!canAfford(total,reserve,3.0))throw new IllegalStateException("Budget reservation would exceed existing ledger cap");
                Files.createDirectories(dir);snapshot(dir,"request.json",canonical);save(dir.resolve("input.json"),JSON.valueToTree(c.input()));
                var record=JSON.createObjectNode().put("caseId",c.id()).put("set",set).put("candidate",candidate).put("promptVersion",VERSION)
                    .put("model",candidate.equals("A")?MODEL:UPPER).put("requestSha256",fingerprint).put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))))
                    .put("requestLayout",SemanticAnalysisV2.PROTOCOL).put("startedAt",Instant.now().toString()).put("status","PENDING")
                    .put("reservedUsd",reserve).put("chargedUsd",reserve).put("campaignBudgetUsd",3.0).put("retries",0)
                    .put("reservationPolicy","v2: explicit default tier; UTF8 bytes+4096 at max input/cache-write rate plus full 12000 output tokens")
                    .put("rateSource","https://developers.openai.com/api/docs/models/"+(candidate.equals("A")?"gpt-5.4-mini":UPPER))
                    .put("costMethod",candidate.equals("A")?"standard usage estimate":"conservative: all uncached input at cache-write rate")
                    .put("reviewStatus","UNREVIEWED; not clinician validated").put("pricingCheckedOn","2026-10-09");
                if(candidate.equals("C"))record.put("parentRequestSha256",hash(canonical(JSON.valueToTree(request(c,"B",key)))));
                record.put("parserRevision","r2").put("parserSha256",hash(read(Path.of("src/main/java/com/kw/knowone/encounter/processing/SemanticAnalysisV2.java"))));
                save(dir.resolve("record.json"),record);long start=System.nanoTime();
                try {
                    var provider=adapter(record.path("model").asText(),key);var response=provider.requestAnalysis(request);save(dir.resolve("response.json"),response);
                    var usage=response.path("usage");record.set("usage",usage);record.put("providerStatus",response.path("status").asText())
                        .put("responseModel",response.path("model").asText()).put("serviceTier",response.path("service_tier").asText());
                    if(usage.path("input_tokens").isNumber()&&usage.path("output_tokens").isNumber()) {
                        double estimate=costBound(usage,!candidate.equals("A"));record.put("estimatedUsd",estimate);
                        if(response.path("service_tier").asText().equals("default"))record.put("chargedUsd",estimate);
                    }
                    var inspection=new SemanticAnalysisV2(JSON,new UtteranceEvidence(JSON,c.input())).inspect(SavedEvidenceAuditMain.output(response));
                    save(dir.resolve("inspection.json"),JSON.valueToTree(inspection));
                    save(dir.resolve("parsed.json"),JSON.valueToTree(provider.parseAnalysis(response,c.input(),VERSION)));
                    record.put("status","PARSED_NOT_QUALITY_APPROVED");
                }catch(Exception e){record.put("status","FAILED").put("errorCode",e instanceof AiProviderException a?a.code():e.getClass().getSimpleName());failed=true;}
                finally {record.put("elapsedMillis",(System.nanoTime()-start)/1_000_000);save(dir.resolve("record.json"),record);}
                System.out.printf(Locale.ROOT,"%s %s %s ledger=$%.8f%n",c.id(),candidate,record.path("status").asText(),charged(ROOT));
            }
            if(failed)throw new IllegalStateException("Some results failed; preserved without retry");
        }
    }
    static List<EvalCase> fresh() throws Exception {
        var cases=new ArrayList<>(publicCases(Path.of("src/test/resources/ai/eval/primock57-v181-final"),"v181-final"));
        var fixture=JSON.readTree(read(Path.of("src/test/resources/ai/eval/v181-final-synthetic.json")));
        for(var c:fixture.path("cases")) {
            String id=c.path("id").asText();
            var source=new AiProcessingPort.SourceText(UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),1,"AUDIO","audio/wav",c.path("text").asText());
            cases.add(new EvalCase(id,new AiProcessingPort.AnalysisInput(java.time.LocalDate.of(2026,10,7),Instant.parse("2026-10-09T00:00:00Z"),"Asia/Seoul",List.of(source),List.of())));
        }
        var frozen=JSON.createObjectNode().put("candidatePlanSha256",hash(read(ROOT.resolve("v1.8.1-plan-r2.json"))));
        var entries=frozen.putArray("inputs");for(var c:cases)entries.addObject().put("caseId",c.id()).put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))));
        var file=ROOT.resolve("v1.8.1-final-inputs-freeze.json");
        if(Files.exists(file)){if(!canonical(frozen).equals(canonical(JSON.readTree(read(file)))))throw new IllegalStateException("Final inputs changed");}else save(file,frozen);
        if(cases.size()!=4)throw new IllegalStateException();return cases;
    }
    static void replay() throws Exception {
        var root=JSON.createObjectNode().put("status","OFFLINE_REPLAY_NOT_SEMANTIC_APPROVAL");var rows=root.putArray("results");
        try(var files=Files.walk(ROOT,2)) {
            for(var file:files.filter(p->p.getFileName().toString().equals("record.json")).sorted().toList()) {
                var r=JSON.readTree(read(file));if(!Set.of("1.8","1.8.1").contains(r.path("promptVersion").asText()))continue;
                if(!Files.exists(file.getParent().resolve("response.json")))continue;
                var input=JSON.treeToValue(JSON.readTree(read(file.getParent().resolve("input.json"))),AiProcessingPort.AnalysisInput.class);
                var response=JSON.readTree(read(file.getParent().resolve("response.json")));var raw=(ObjectNode)JSON.readTree(SavedEvidenceAuditMain.output(response));raw.put("schemaVersion",VERSION);
                var revised=new SemanticAnalysisV2(JSON,new UtteranceEvidence(JSON,input));var row=rows.addObject();row.set("record",r);row.set("inspection",JSON.valueToTree(revised.inspect(raw.toString())));
                try {var output=new AnalysisOutputValidator(JSON).validate(revised.resolve(raw.toString()),input,"OPENAI",r.path("model").asText(),VERSION,0,0);row.put("replay","ACCEPTED");row.set("resolved",JSON.valueToTree(output));}
                catch(AiProviderException e){row.put("replay",e.code());}
            }
        }
        save(ROOT.resolve("v1.8.1-replay.json"),root);System.out.println("Replayed "+rows.size()+" saved responses; no calls, no semantic approval");
    }
}
