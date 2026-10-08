package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

class SummaryComparisonTests {
    @TempDir Path temp;
    final Path source=SummaryEvidenceEvaluationMain.RESULTS.resolve("14b888cebcc60c5ee8ccbf113d8c51a524725d7f3073bd82f8a35ea8c193d8d8");
    EvalCase example() throws Exception {return cases("holdout").stream().filter(c->c.id().equals("day5_consultation12")).findFirst().orElseThrow();}

    @Test void exactRequestReuseCostsNothingAndPreservesOriginal() throws Exception {
        String before=read(source.resolve("record.json"));
        String request=canonical(JSON.readTree(read(source.resolve("request.json"))));
        Path target=temp.resolve("reused");
        assertTrue(reuseStored(source,target,request,example(),"reviewed-development",3));
        var copy=JSON.readTree(read(target.resolve("record.json")));
        assertTrue(copy.path("reused").asBoolean());assertEquals(0,copy.path("chargedUsd").asDouble());
        assertTrue(copy.path("originalChargedUsd").asDouble()>0);assertEquals(3,copy.path("campaignBudgetUsd").asDouble());
        assertEquals(before,read(source.resolve("record.json")));
        assertArrayEquals(Files.readAllBytes(source.resolve("response.json")),Files.readAllBytes(target.resolve("response.json")));
        assertEquals(0,charged(temp));
    }
    @Test void changedRequestCannotBeMistakenForCachedResult() throws Exception {
        String changed=read(source.resolve("request.json")).replace("gpt-5.4-mini-2026-03-17","different-model");
        assertThrows(IllegalStateException.class,()->reuseStored(source,temp.resolve("rejected"),changed,example(),"reviewed-development",3));
        assertFalse(Files.exists(temp.resolve("rejected")));
    }
    @Test void additionalThreeDollarBudgetIncludesNextWorstCaseCall() {
        assertEquals(3,SummaryEvidenceEvaluationMain.V17_BUDGET);
        assertFalse(canAfford(2.99,.02,SummaryEvidenceEvaluationMain.V17_BUDGET));
        assertTrue(canAfford(2.8,.19,SummaryEvidenceEvaluationMain.V17_BUDGET));
    }
    @Test void storedPairsHaveIdenticalInputsAndSettingsExceptPrompt() throws Exception {
        Path root=SummaryEvidenceEvaluationMain.V17_RESULTS;
        var pairs=new java.util.HashMap<String,java.util.Map<String,Path>>();
        try(var files=Files.walk(root,2)) {
            for(var file:files.filter(p->p.getFileName().toString().equals("record.json")).toList()) {
                var record=JSON.readTree(read(file));
                if(!java.util.Set.of("1.6","1.7").contains(record.path("promptVersion").asText()))continue;
                if(record.path("set").asText().equals("semantic-regression"))continue;
                pairs.computeIfAbsent(record.path("caseId").asText(),k->new java.util.HashMap<>()).put(record.path("promptVersion").asText(),file.getParent());
            }
        }
        assertEquals(20,pairs.size());
        for(var pair:pairs.values()) {
            assertEquals(2,pair.size());
            var before=JSON.readTree(read(pair.get("1.6").resolve("request.json")));
            var after=JSON.readTree(read(pair.get("1.7").resolve("request.json")));
            assertNotEquals(before.at("/input/0/content/0/text"),after.at("/input/0/content/0/text"));
            ((tools.jackson.databind.node.ObjectNode)before.at("/input/0/content/0")).put("text","PROMPT_EXCLUDED");
            ((tools.jackson.databind.node.ObjectNode)after.at("/input/0/content/0")).put("text","PROMPT_EXCLUDED");
            assertEquals(canonical(before),canonical(after));
            assertEquals(JSON.readTree(read(pair.get("1.6").resolve("input.json"))),JSON.readTree(read(pair.get("1.7").resolve("input.json"))));
        }
    }
}
