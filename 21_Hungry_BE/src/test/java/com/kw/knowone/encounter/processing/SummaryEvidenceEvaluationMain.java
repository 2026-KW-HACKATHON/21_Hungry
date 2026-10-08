package com.kw.knowone.encounter.processing;

import java.nio.file.Path;
import java.util.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** Separate fixed $1 ledger. Same adapter, transport and parser as opt-in production protocol. */
public final class SummaryEvidenceEvaluationMain {
    static final Path RESULTS=Path.of("eval-results/evidence-linking-2026-10-09");
    static final Path NEW_DATA=Path.of("src/test/resources/ai/eval/primock57-evidence-holdout");
    static final String V17_CAMPAIGN="v1.7-quality";
    static final Path V17_RESULTS=Path.of("eval-results/prompt-v1.7-quality-2026-10-09");
    static final double V17_BUDGET=3.0;
    public static void main(String[] args) {
        try {
            if(args.length>0&&args[0].equals("freeze-v18")){freezeV18(false);return;}
            if(args.length>0&&args[0].equals("freeze")){freeze(args.length>1?args[1]:"1.6");return;}
            String campaign=args.length>3?args[3]:"evidence-linking";
            if(campaign.equals("v1.8-compare")){SemanticComparisonMain.run(args);return;}
            if(campaign.equals("v1.8-semantics")) {
                if(args.length<2||!Set.of("1.6","1.7","1.8-prompt","1.8").contains(args[1])||!Set.of("synthetic","reviewed-development","semantic-regression","v18-holdout","model-comparison").contains(args[0]))throw new IllegalArgumentException();
                // Continue the existing $3 ledger; no fresh budget or deletion of prior costs.
                if(args[0].equals("v18-holdout")){if(!args[1].equals("1.8"))throw new IllegalArgumentException();freezeV18(true);}
                runCampaign(args,V17_RESULTS,V17_BUDGET,true,RESULTS);
            } else if(campaign.equals(V17_CAMPAIGN)) {
                if(args.length<2||!Set.of("1.6","1.7").contains(args[1])||!Set.of("synthetic","reviewed-development").contains(args[0]))throw new IllegalArgumentException();
                freezeComparison();
                runCampaign(args,V17_RESULTS,V17_BUDGET,true,RESULTS);
            } else if(campaign.equals("evidence-linking"))runCampaign(args,RESULTS,1.0,true);
            else throw new IllegalArgumentException();
        }
        catch(Exception e){System.err.println("Evidence evaluation stopped: "+(e instanceof AiProviderException a?a.code():e.getClass().getSimpleName()));System.exit(1);}
    }
    static void freezeV18(boolean verifyOnly) throws Exception {
        var file=V17_RESULTS.resolve("v1.8-candidate-freeze.json");
        var frozen=JSON.createObjectNode().put("candidate","1.8").put("beforeHoldoutSourceInspection",true)
            .put("holdoutPolicy","Run all four even if development fails; never tune after inspection; not promotion approval");
        var artifacts=frozen.putObject("artifacts");
        for(String path:List.of("src/main/resources/ai/analysis-prompt-v1.8.txt","src/main/resources/ai/analysis-semantic-facts.schema.json",
            "src/main/java/com/kw/knowone/encounter/processing/SemanticAnalysis.java","src/main/java/com/kw/knowone/encounter/processing/UtteranceEvidence.java"))artifacts.put(path,hash(read(Path.of(path))));
        frozen.set("unseenCaseIds",JSON.valueToTree(List.of("day1_consultation03","day2_consultation04","day4_consultation01","day5_consultation01")));
        if(java.nio.file.Files.exists(file)) {
            if(!canonical(frozen).equals(canonical(JSON.readTree(read(file)))))throw new IllegalStateException("Frozen candidate changed");
        }else{
            if(verifyOnly)throw new IllegalStateException("Freeze missing");
            java.nio.file.Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(frozen),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
    static void freezeComparison() throws Exception {
        var plan=JSON.createObjectNode().put("campaign",V17_CAMPAIGN).put("budgetUsd",V17_BUDGET).put("model",MODEL)
            .put("maxOutputTokens",OUTPUT_LIMIT).put("reasoning","unchanged provider default; no explicit reasoning parameter")
            .put("selectionRule","Only proceed to four fresh held-out cases after development has no important errors or regressions")
            .put("review","Codex AI source comparison, not clinician validation");
        var hashes=plan.putObject("frozenArtifacts");
        for(String path:List.of("src/main/resources/ai/analysis-prompt-v1.6.txt","src/main/resources/ai/analysis-prompt-v1.7.txt",
            "src/main/java/com/kw/knowone/encounter/processing/UtteranceEvidence.java","src/main/java/com/kw/knowone/encounter/processing/OpenAiProcessingAdapter.java"))
            hashes.put(path,hash(read(Path.of(path))));
        var inputs=plan.putArray("developmentInputs");
        for(String set:List.of("synthetic","reviewed-development"))for(var c:campaignCases(set))
            inputs.addObject().put("caseId",c.id()).put("set",set).put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))));
        java.nio.file.Files.createDirectories(V17_RESULTS);
        var file=V17_RESULTS.resolve("comparison-plan.json");
        if(java.nio.file.Files.exists(file)) {
            if(!canonical(plan).equals(canonical(JSON.readTree(read(file)))))throw new IllegalStateException("Frozen comparison changed");
        } else java.nio.file.Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    static void freeze(String version) throws Exception {
        if(!Set.of("1.5","1.6").contains(version))throw new IllegalArgumentException();
        var p=RESULTS.resolve("candidate-freeze-v"+version+".json");
        var freeze=JSON.createObjectNode().put("promptVersion",version)
            .put("promptSha256",hash(read(Path.of("src/main/resources/ai/analysis-prompt-v"+version+".txt"))))
            .put("resolverSha256",hash(read(Path.of("src/main/java/com/kw/knowone/encounter/processing/UtteranceEvidence.java"))))
            .put("frozenAt",java.time.Instant.now().toString()).put("promotion","BLOCKED; semantic regressions in development; held-out diagnostic evaluation only");
        var cases=freeze.putArray("newHoldout");
        for(var c:campaignCases("new-holdout"))cases.addObject().put("caseId",c.id()).put("inputSha256",hash(canonical(JSON.valueToTree(c.input()))));
        // Never silently re-freeze after seeing held-out outputs.
        java.nio.file.Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(freeze),java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Candidate frozen for new held-out diagnostic evaluation; NOT approved for production.");
    }
    static List<EvalCase> campaignCases(String set) throws Exception {
        return switch(set) {
            case "synthetic" -> syntheticCases();
            case "semantic-regression" -> semanticCases();
            case "model-comparison" -> {var all=new ArrayList<>(campaignCases("synthetic"));all.addAll(campaignCases("reviewed-development"));yield all.stream().filter(c->Set.of("D01_DOCTOR_CONDITIONAL_RETURN","D11_DOCUMENT_CONFLICT","H01_NOVEL_MIXED_CORRECTION","day1_consultation02","day3_consultation05","day3_consultation08").contains(c.id())).toList();}
            case "v18-holdout" -> publicCases(Path.of("src/test/resources/ai/eval/primock57-v18-holdout"),"v18-holdout");
            case "public-development" -> {var all=new ArrayList<>(cases("development"));all.addAll(cases("holdout"));yield all;}
            case "reviewed-development" -> {var all=new ArrayList<>(campaignCases("public-development"));all.addAll(publicCases(NEW_DATA,"new-holdout"));yield all;}
            case "new-holdout" -> publicCases(NEW_DATA,"new-holdout");
            default -> throw new IllegalArgumentException("Previous holdout must now be development");
        };
    }
    static List<EvalCase> semanticCases() throws Exception {
        var cases=new ArrayList<EvalCase>();
        for(var c:JSON.readTree(read(Path.of("src/test/resources/ai/eval/semantic-regressions-v1.0.json"))).path("cases")) {
            String id=c.path("id").asText();
            var source=new AiProcessingPort.SourceText(UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),1,"AUDIO","audio/wav",c.path("transcript").asText());
            cases.add(new EvalCase(id,new AiProcessingPort.AnalysisInput(java.time.LocalDate.of(2026,10,7),java.time.Instant.parse("2026-10-09T00:00:00Z"),"Asia/Seoul",List.of(source),List.of())));
        }
        return cases;
    }
}
