package com.kw.knowone.encounter.processing;

import com.kw.knowone.encounter.processing.AiProcessingPort.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.net.http.HttpClient;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public mock data only. No Spring context/repositories, STT, translation or judge API. */
public final class SummaryEvaluationMain {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DATA = Path.of("src/test/resources/ai/eval/primock57");
    static final Path RESULTS = Path.of("eval-results/primock57-2026-10-09");
    static final String MODEL = "gpt-5.4-mini-2026-03-17";
    static final String COMPARISON_MODEL = "gpt-5.4-2026-03-05";
    static final int OUTPUT_LIMIT = 12000;
    static final double BUDGET = 3.0;
    // Official standard rates, checked 2026-10-09. Reasoning is included in output_tokens.
    static final double INPUT_RATE = .75/1_000_000, CACHED_RATE = .075/1_000_000, OUTPUT_RATE = 4.50/1_000_000;

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            // Never print exception messages/stack traces that might contain provider text or credentials.
            System.err.println("Evaluation stopped: " + (e instanceof AiProviderException a ? a.code() : e.getClass().getSimpleName()));
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        runCampaign(args,RESULTS,BUDGET,false);
    }
    static void runCampaign(String[] args,Path results,double budget,boolean utteranceIds) throws Exception {
        runCampaign(args,results,budget,utteranceIds,null);
    }
    static void runCampaign(String[] args,Path results,double budget,boolean utteranceIds,Path reuseRoot) throws Exception {
        String set=args.length>0?args[0]:"development", version=args.length>1?args[1]:"1.1";
        boolean live=args.length>2&&args[2].equals("true");
        String modelChoice=args.length>4?args[4]:"mini";
        if(!Set.of("mini","gpt-5.4").contains(modelChoice))throw new IllegalArgumentException();
        boolean upper=modelChoice.equals("gpt-5.4");
        if(upper&&(!utteranceIds||!version.equals("1.8")||!set.equals("model-comparison")))throw new IllegalArgumentException();
        String selectedModel=upper?COMPARISON_MODEL:MODEL;
        String protocol=!utteranceIds?"legacy":switch(version){case "1.5" -> UtteranceEvidence.PROTOCOL;case "1.6", "1.7", "1.8-prompt" -> UtteranceEvidence.GROUNDED_PROTOCOL;case "1.8" -> SemanticAnalysis.PROTOCOL;default -> throw new IllegalArgumentException();};
        if(!utteranceIds&&Set.of("1.5","1.6","1.7").contains(version))throw new IllegalArgumentException("ID prompt requires the evidence evaluation runner");
        // The two previously new held-out cases have now informed the reference merge.
        if(utteranceIds&&version.equals("1.7")&&set.equals("new-holdout"))throw new IllegalArgumentException("Fresh held-out data required for 1.7");
        if(!Set.of("development","holdout","synthetic","public-development","reviewed-development","new-holdout","semantic-regression","v18-holdout","model-comparison").contains(set)||!version.matches("[0-9]+\\.[0-9]+(?:-prompt)?"))throw new IllegalArgumentException();
        if(!utteranceIds&&Set.of("public-development","reviewed-development","new-holdout").contains(set))throw new IllegalArgumentException();
        validateDataset();
        Files.createDirectories(results);
        // One immutable campaign ledger across prompts/sets/processes. No configurable budget/output directory.
        try(var channel=FileChannel.open(results.resolve("campaign.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException();
            var config=configuration();
            if(!MODEL.equals(config.getOrDefault("OPENAI_ANALYSIS_MODEL",config.getOrDefault("OPENAI_ANALYZE_MODEL",MODEL))))throw new IllegalStateException();
            if(!"https://api.openai.com/v1".equals(config.getOrDefault("OPENAI_BASE_URL","https://api.openai.com/v1")))throw new IllegalStateException();
            if(OUTPUT_LIMIT!=Integer.parseInt(config.getOrDefault("OPENAI_MAX_OUTPUT_TOKENS",config.getOrDefault("AI_MAX_OUTPUT_TOKENS","12000"))))throw new IllegalStateException();
            String key=config.getOrDefault("OPENAI_API_KEY","");
            if(live&&key.isBlank())throw new AiProviderException("AI_NOT_CONFIGURED",false);
            Duration timeout=Duration.ofSeconds(Long.parseLong(config.getOrDefault("AI_CALL_TIMEOUT_SECONDS","90")));
            String duration=config.getOrDefault("OPENAI_TIMEOUT","");
            if(!duration.isBlank())timeout=duration.startsWith("P")?Duration.parse(duration):Duration.ofSeconds(Long.parseLong(duration.replaceFirst("s$","")));
            var adapter=new OpenAiProcessingAdapter(JSON,new AnalysisOutputValidator(JSON),"https://api.openai.com/v1",key,
                "gpt-transcribe",MODEL,selectedModel,timeout,OUTPUT_LIMIT,HttpClient.newBuilder().connectTimeout(timeout).build(),protocol,utteranceIds?version:"");
            String prompt=read(Path.of("src/main/resources/ai/analysis-prompt-v"+version+".txt"));
            String guidelines=read(Path.of("docs/AI-SUMMARY-GUIDELINES.md"));
            if(live&&(set.equals("holdout")||set.equals("new-holdout"))) {
                var freeze=JSON.readTree(read(results.resolve(utteranceIds?"candidate-freeze-v"+version+".json":"candidate-freeze.json")));
                if(!hash(prompt).equals(freeze.path("promptSha256").asText()))throw new IllegalStateException();
                if(utteranceIds&&!hash(read(Path.of("src/main/java/com/kw/knowone/encounter/processing/UtteranceEvidence.java"))).equals(freeze.path("resolverSha256").asText()))throw new IllegalStateException();
            }
            snapshot(results,"prompt-"+hash(prompt)+".txt",prompt);
            snapshot(results,"guidelines-"+hash(guidelines)+".md",guidelines);
            if(utteranceIds)for(String name:List.of("UtteranceEvidence","OpenAiProcessingAdapter","SemanticAnalysis")) {
                String source=read(Path.of("src/main/java/com/kw/knowone/encounter/processing/"+name+".java"));
                snapshot(results,name+"-"+hash(source)+".java.txt",source);
            }
            if(version.equals("1.8")) {
                String schema=read(Path.of("src/main/resources/ai/analysis-semantic-facts.schema.json"));
                snapshot(results,"semantic-schema-"+hash(schema)+".json",schema);
            }
            var cases=utteranceIds?SummaryEvidenceEvaluationMain.campaignCases(set):cases(set);
            boolean failed=false;
            for(var c:cases) {
                if(live&&set.equals("new-holdout")) {
                    var freeze=JSON.readTree(read(results.resolve("candidate-freeze-v"+version+".json")));
                    boolean match=false;for(var frozen:freeze.path("newHoldout"))if(c.id().equals(frozen.path("caseId").asText())&&hash(canonical(JSON.valueToTree(c.input()))).equals(frozen.path("inputSha256").asText()))match=true;
                    if(!match)throw new IllegalStateException();
                }
                // Explicit, recorded experimental layout; production stays on its unmodified layout.
                var request=adapter.analysisRequest(c.input(),prompt,true);
                String requestJson=canonical(JSON.valueToTree(request));
                String fingerprint=hash(requestJson);
                Path folder=results.resolve(fingerprint);
                Path recordFile=folder.resolve("record.json");
                if(!Files.exists(recordFile)&&reuseRoot!=null)
                    reuseStored(reuseRoot.resolve(fingerprint),folder,requestJson,c,set,budget);
                if(Files.exists(recordFile)) {
                    var cached=JSON.readTree(read(recordFile));
                    verifyCachedRequest(folder,requestJson);
                    if(!hash(canonical(JSON.valueToTree(c.input()))).equals(cached.path("inputSha256").asText()))throw new IllegalStateException();
                    if(utteranceIds&&!Files.exists(folder.resolve("input.json")))save(folder.resolve("input.json"),JSON.valueToTree(c.input()));
                    String cachedStatus=cached.path("status").asText();
                    System.out.println(c.id()+" reused: "+cachedStatus);
                    if(!"PARSED_NOT_QUALITY_APPROVED".equals(cachedStatus))failed=true;
                    continue; // Includes failures/pending: never silently retry, erase cost or select a lucky sample.
                }
                double reservation=upper?reserveUpper(requestJson):reserve(requestJson);
                double total=charged(results);
                System.out.printf(Locale.ROOT,"%s %s max-next=$%.6f charged=$%.6f%n",c.id(),live?"LIVE":"DRY",reservation,total);
                if(!live)continue;
                if(!canAfford(total,reservation,budget))throw new IllegalStateException();
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("request.json"),requestJson,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                if(utteranceIds)save(folder.resolve("input.json"),JSON.valueToTree(c.input()));
                ObjectNode record=JSON.createObjectNode();
                record.put("caseId",c.id()).put("set",set).put("promptVersion",version).put("model",selectedModel)
                    .put("guidelineSha256",hash(guidelines)).put("promptSha256",hash(prompt)).put("requestSha256",fingerprint)
                    .put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))))
                    .put("requestLayout",utteranceIds?protocol:"evidence-first-v1").put("startedAt",Instant.now().toString()).put("status","PENDING")
                    .put("reservedUsd",reservation).put("chargedUsd",reservation)
                    .put("rateSource","https://developers.openai.com/api/docs/models/"+(upper?"gpt-5.4":"gpt-5.4-mini"))
                    .put("pricingCheckedOn","2026-10-09").put("reviewStatus","unreviewed; not clinician validated");
                record.put("campaignBudgetUsd",budget);
                if(utteranceIds)record.put("resolverSha256",hash(read(Path.of("src/main/java/com/kw/knowone/encounter/processing/UtteranceEvidence.java"))));
                save(recordFile,record); // Reserve durably BEFORE send, including crashes/timeouts.
                long started=System.nanoTime();
                try {
                    JsonNode response=adapter.requestAnalysis(request);
                    save(folder.resolve("response.json"),response);
                    JsonNode usage=response.path("usage");
                    record.set("usage",usage);
                    record.put("responseModel",response.path("model").asText());
                    record.put("serviceTier",response.path("service_tier").asText("unknown"));
                    record.put("providerStatus",response.path("status").asText());
                    if(usage.path("input_tokens").isNumber()&&usage.path("output_tokens").isNumber()) {
                        double cost=upper?costUpper(usage):cost(usage);
                        record.put("estimatedUsd",cost);
                        // Unexpected tier is conservatively held at the reservation until investigated.
                        record.put("chargedUsd","default".equals(response.path("service_tier").asText())?cost:reservation);
                    }
                    var result=adapter.parseAnalysis(response,c.input(),version);
                    save(folder.resolve("parsed.json"),JSON.valueToTree(result));
                    record.put("status","PARSED_NOT_QUALITY_APPROVED");
                } catch(Exception e) {
                    record.put("status","FAILED");
                    record.put("errorCode",e instanceof AiProviderException a?a.code():e.getClass().getSimpleName());
                } finally {
                    record.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
                    save(recordFile,record);
                }
                System.out.printf(Locale.ROOT,"%s %s charged-total=$%.6f%n",c.id(),record.path("status").asText(),charged(results));
                if(!"PARSED_NOT_QUALITY_APPROVED".equals(record.path("status").asText())) {
                    failed=true;
                    if(Set.of("AI_NOT_CONFIGURED","AI_TIMEOUT","AI_PROVIDER_UNAVAILABLE","AI_RATE_LIMITED").contains(record.path("errorCode").asText()))break;
                }
            }
            if(failed)throw new IllegalStateException();
        }
    }

    static boolean reuseStored(Path source,Path target,String request,EvalCase c,String set,double budget) throws Exception {
        if(!Files.exists(source.resolve("record.json")))return false;
        var original=JSON.readTree(read(source.resolve("record.json")));
        String inputHash=hash(canonical(JSON.valueToTree(c.input())));
        if(!hash(request).equals(original.path("requestSha256").asText())
            ||!inputHash.equals(original.path("inputSha256").asText())
            ||!request.equals(canonical(JSON.readTree(read(source.resolve("request.json"))))))
            throw new IllegalStateException("Stored request/input mismatch; refusing reuse or replacement call");
        // Keep the original ledger immutable. All statuses, including failure/pending, are reused, never retried.
        Files.createDirectories(target);
        for(String name:List.of("request.json","response.json","parsed.json","input.json"))
            if(Files.exists(source.resolve(name))&&!Files.exists(target.resolve(name)))Files.copy(source.resolve(name),target.resolve(name));
        if(!Files.exists(target.resolve("input.json")))save(target.resolve("input.json"),JSON.valueToTree(c.input()));
        var copy=(ObjectNode)original.deepCopy();
        copy.put("reusedFrom",source.toString().replace('\\','/')).put("reused",true).put("originalSet",original.path("set").asText())
            .put("set",set).put("originalChargedUsd",original.path("chargedUsd").asDouble()).put("originalEstimatedUsd",original.path("estimatedUsd").asDouble())
            .put("chargedUsd",0).put("estimatedUsd",0).put("campaignBudgetUsd",budget);
        save(target.resolve("record.json"),copy);
        return true;
    }

    static void verifyCachedRequest(Path folder,String request) throws Exception {
        var record=JSON.readTree(read(folder.resolve("record.json")));
        if(!hash(request).equals(record.path("requestSha256").asText())
            ||!request.equals(canonical(JSON.readTree(read(folder.resolve("request.json"))))))
            throw new IllegalStateException("Cached full request mismatch; refusing reuse or retry");
    }

    static void validateDataset() throws Exception {
        JsonNode manifest=JSON.readTree(read(DATA.resolve("manifest.json")));
        if(manifest.path("cases").size()!=10||!manifest.path("selectedBeforeEvaluation").asBoolean())throw new IllegalStateException();
        int dev=0,holdout=0;
        for(JsonNode entry:manifest.path("cases")) {
            JsonNode en=JSON.readTree(read(DATA.resolve(entry.path("englishFile").asText())));
            JsonNode ko=JSON.readTree(read(DATA.resolve(entry.path("translationFile").asText())));
            if(en.path("turns").size()!=ko.path("turns").size()||ko.path("facts").isEmpty())throw new IllegalStateException();
            if(!ko.path("translationStatus").asText().contains("not clinician validated"))throw new IllegalStateException();
            Set<String> ids=new HashSet<>();
            for(var turn:en.path("turns"))ids.add(turn.path("id").asText());
            for(var fact:ko.path("facts"))for(var id:fact.path("turnIds"))if(!ids.contains(id.asText()))throw new IllegalStateException();
            for(var turn:ko.path("turns"))if(!turn.isTextual()||turn.asText().isBlank())throw new IllegalStateException();
            if("development".equals(entry.path("set").asText()))dev++;else if("holdout".equals(entry.path("set").asText()))holdout++;
        }
        if(dev!=6||holdout!=4)throw new IllegalStateException();
    }

    static List<EvalCase> cases(String set) throws Exception {
        if(set.equals("synthetic"))return syntheticCases();
        return publicCases(DATA,set);
    }
    static List<EvalCase> publicCases(Path data,String set) throws Exception {
        List<EvalCase> cases=new ArrayList<>();
        for(var entry:JSON.readTree(read(data.resolve("manifest.json"))).path("cases")) {
            if(!set.equals(entry.path("set").asText()))continue;
            String id=entry.path("id").asText();
            var en=JSON.readTree(read(data.resolve(entry.path("englishFile").asText())));
            var ko=JSON.readTree(read(data.resolve(entry.path("translationFile").asText())));
            if(en.path("turns").size()!=ko.path("turns").size()||ko.path("facts").isEmpty())throw new IllegalStateException();
            StringBuilder transcript=new StringBuilder();
            for(int i=0;i<en.path("turns").size();i++) {
                var turn=en.path("turns").get(i);
                transcript.append('[').append(turn.path("id").asText()).append("] ")
                    .append(turn.path("speaker").asText().equals("doctor")?"의사: ":"환자: ")
                    .append(ko.path("turns").get(i).asText()).append('\n');
            }
            // Fixed synthetic context, NOT a claim about the original consultation date. Notes are never input.
            var source=new SourceText(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString(),1,"AUDIO","audio/wav",transcript.toString());
            cases.add(new EvalCase(id,new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-09T00:00:00Z"),"Asia/Seoul",List.of(source),List.of())));
        }
        return cases;
    }

    static List<EvalCase> syntheticCases() throws Exception {
        var fixture=JSON.readTree(read(Path.of("src/test/resources/ai/eval/summary-cases-v0.1.0.json")));
        List<EvalCase> cases=new ArrayList<>();
        for(var c:fixture.path("cases")) {
            List<SourceText> sources=new ArrayList<>();
            for(var s:c.path("sources"))sources.add(new SourceText(UUID.nameUUIDFromBytes((c.path("id").asText()+sources.size()).getBytes(StandardCharsets.UTF_8)).toString(),
                1,s.path("sourceType").asText(),s.path("mediaType").asText(),s.path("text").asText()));
            cases.add(new EvalCase(c.path("id").asText(),new AnalysisInput(LocalDate.parse(fixture.path("occurredOn").asText()),
                Instant.parse("2026-10-09T00:00:00Z"),fixture.path("timezone").asText(),sources,List.of())));
        }
        if(cases.isEmpty())throw new IllegalStateException();
        return cases;
    }

    static double reserve(String request) {
        // Byte count upper bound (incl schema) + framing allowance, uncached input and FULL output cap.
        // 2x safety allowance also covers an unexpected priority tier. Never assume a cache discount before send.
        return 2*((request.getBytes(StandardCharsets.UTF_8).length+4096)*INPUT_RATE+OUTPUT_LIMIT*OUTPUT_RATE);
    }
    static boolean canAfford(double charged,double next) {return canAfford(charged,next,BUDGET);}
    static double reserveUpper(String request){return 2*((request.getBytes(StandardCharsets.UTF_8).length+4096)*2.50/1_000_000+OUTPUT_LIMIT*15.0/1_000_000);}
    static double costUpper(JsonNode usage){long in=usage.path("input_tokens").asLong(),out=usage.path("output_tokens").asLong();long cache=Math.max(0,Math.min(in,usage.at("/input_tokens_details/cached_tokens").asLong()));return ((in-cache)*2.50+cache*.25+out*15.0)/1_000_000;}
    static boolean canAfford(double charged,double next,double budget) {return charged>=0&&next>=0&&budget>0&&charged+next<=budget;}
    static double cost(JsonNode usage) {
        long in=usage.path("input_tokens").asLong(),out=usage.path("output_tokens").asLong();
        long cache=Math.max(0,Math.min(in,usage.path("input_tokens_details").path("cached_tokens").asLong()));
        return (in-cache)*INPUT_RATE+cache*CACHED_RATE+out*OUTPUT_RATE;
    }
    static double charged() throws Exception {
        return charged(RESULTS);
    }
    static double charged(Path results) throws Exception {
        double sum=0;
        try(var paths=Files.walk(results,2)) {
            for(var p:paths.filter(p->p.getFileName().toString().equals("record.json")).toList()) {
                var record=JSON.readTree(read(p));
                if(!record.path("chargedUsd").isNumber())throw new IllegalStateException();
                sum+=record.path("chargedUsd").asDouble();
            }
        }
        return sum;
    }
    static Map<String,String> configuration() throws Exception {
        var config=new HashMap<String,String>();
        Path env=Path.of("secrets/openai.env");
        if(Files.exists(env))for(String line:Files.readAllLines(env,StandardCharsets.UTF_8)) {
            int equal=line.indexOf('=');if(equal<1)continue;
            String k=line.substring(0,equal).trim(),v=line.substring(equal+1).trim();
            if((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))v=v.substring(1,v.length()-1);
            if(k.startsWith("OPENAI_")||k.startsWith("AI_"))config.put(k,v);
        }
        System.getenv().forEach((k,v)->{if(k.startsWith("OPENAI_")||k.startsWith("AI_"))config.put(k,v);});
        return config;
    }
    static String canonical(JsonNode node) {
        // Schema property insertion order affects Structured Outputs generation; include it in cache identity.
        if(node.isObject()&&node.has("properties"))return node.toString();
        if(node.isObject()) {var sorted=new TreeMap<String,JsonNode>();node.properties().forEach(e->sorted.put(e.getKey(),e.getValue()));
            return "{"+String.join(",",sorted.entrySet().stream().map(e->JSON.writeValueAsString(e.getKey())+":"+canonical(e.getValue())).toList())+"}";}
        if(node.isArray()){var parts=new ArrayList<String>();node.forEach(n->parts.add(canonical(n)));return "["+String.join(",",parts)+"]";}
        return node.toString();
    }
    static String hash(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static String read(Path path) throws Exception {return Files.readString(path,StandardCharsets.UTF_8).replaceFirst("^\uFEFF","");}
    static void snapshot(String name,String text) throws Exception {snapshot(RESULTS,name,text);}
    static void snapshot(Path root,String name,String text) throws Exception {Path p=root.resolve(name);if(!Files.exists(p))Files.writeString(p,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    static void save(Path path,JsonNode node) throws Exception {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temp,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),StandardCharsets.UTF_8);
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    record EvalCase(String id,AnalysisInput input) { }
}
