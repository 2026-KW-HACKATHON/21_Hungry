package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

class SemanticCampaignTests {
    List<JsonNode> rows() throws Exception {
        var result=new ArrayList<JsonNode>();try(var files=Files.walk(SummaryEvidenceEvaluationMain.V17_RESULTS,2)) {
            for(var file:files.filter(p->p.getFileName().toString().equals("record.json")).toList()) {
                var record=JSON.readTree(read(file));
                if(!record.path("promptVersion").asText().equals("1.8.1"))result.add(record);
            }
        }return result;
    }
    JsonNode request(JsonNode r) throws Exception{return JSON.readTree(read(SummaryEvidenceEvaluationMain.V17_RESULTS.resolve(r.path("requestSha256").asText()).resolve("request.json")));}
    @Test void fixedCandidateAndFourNewSourcesRemainDisjoint() throws Exception {
        SummaryEvidenceEvaluationMain.freezeV18(true);
        var old=new HashSet<String>();for(var c:SummaryEvidenceEvaluationMain.campaignCases("reviewed-development"))old.add(c.id());
        var fresh=SummaryEvidenceEvaluationMain.campaignCases("v18-holdout");assertEquals(4,fresh.size());for(var c:fresh)assertFalse(old.contains(c.id()));
    }
    @Test void all547TranslatedTurnsHaveSourceAlignmentAndReviewLabels() throws Exception {
        var root=Path.of("src/test/resources/ai/eval/primock57-v18-holdout");int total=0;
        for(var c:JSON.readTree(read(root.resolve("manifest.json"))).path("cases")) {
            var en=JSON.readTree(read(root.resolve(c.path("englishFile").asText())));var ko=JSON.readTree(read(root.resolve(c.path("translationFile").asText())));
            int n=en.path("turns").size();assertEquals(n,ko.path("turns").size());assertEquals(n,c.path("utterances").asInt());total+=n;
            assertTrue(ko.path("translationStatus").asText().contains("not clinician validated"));assertFalse(ko.path("facts").isEmpty());
            var ids=new HashSet<String>();en.path("turns").forEach(t->ids.add(t.path("id").asText()));
            for(var f:ko.path("facts")){var refs=f.has("turnIds")?f.path("turnIds"):f.path("sourceTurns");assertFalse(refs.isEmpty());for(var id:refs)assertTrue(ids.contains(id.asText()));}
            for(var turn:ko.path("turns"))assertFalse(turn.asText().isBlank());
        }assertEquals(547,total);
    }
    @Test void twentyPromptOnlyPairsChangeOnlyPrompt() throws Exception {
        var all=rows();int checked=0;
        for(var after:all)if(after.path("promptVersion").asText().equals("1.8-prompt")) {
            var before=all.stream().filter(r->r.path("caseId").equals(after.path("caseId"))&&r.path("promptVersion").asText().equals("1.7")).findFirst().orElseThrow();
            var a=request(after);var b=request(before);
            ((ObjectNode)a.at("/input/0/content/0")).put("text","EXCLUDED");((ObjectNode)b.at("/input/0/content/0")).put("text","EXCLUDED");
            assertEquals(canonical(a),canonical(b));assertEquals(before.path("inputSha256"),after.path("inputSha256"));checked++;
        }assertEquals(20,checked);
    }
    @Test void sixUpperModelPairsChangeOnlyModel() throws Exception {
        var all=rows();int checked=0;
        for(var upper:all)if(upper.path("model").asText().equals(COMPARISON_MODEL)) {
            var mini=all.stream().filter(r->r.path("caseId").equals(upper.path("caseId"))&&r.path("promptVersion").asText().equals("1.8")&&r.path("model").asText().equals(MODEL)).findFirst().orElseThrow();
            var a=(ObjectNode)request(upper);var b=(ObjectNode)request(mini);assertNotEquals(a.path("model"),b.path("model"));a.remove("model");b.remove("model");assertEquals(canonical(a),canonical(b));checked++;
        }assertEquals(6,checked);
    }
    @Test void structureChangesOnlyProtocolAndPromptNotClinicalInputOrGenerationSettings() throws Exception {
        var all=rows();int checked=0;
        for(var a:all)if(a.path("promptVersion").asText().equals("1.8-prompt")) {
            var b=all.stream().filter(r->r.path("caseId").equals(a.path("caseId"))&&r.path("promptVersion").asText().equals("1.8")&&r.path("model").asText().equals(MODEL)).findFirst().orElseThrow();
            assertEquals(a.path("inputSha256"),b.path("inputSha256"));var ar=request(a);var br=request(b);
            for(String key:List.of("model","max_output_tokens","store","temperature","reasoning"))assertEquals(ar.path(key),br.path(key));
            assertEquals(ar.at("/input/1"),br.at("/input/1"));checked++;
        }assertEquals(20,checked);
    }
    @Test void upperPriceAndWorstCaseReservationAreSeparateAndBudgetIncludesHistory() throws Exception {
        var usage=JSON.readTree("{\"input_tokens\":1000000,\"output_tokens\":1000,\"input_tokens_details\":{\"cached_tokens\":100000}}");
        assertEquals(2.29,costUpper(usage),.0000001);assertTrue(reserveUpper("{}")>reserve("{}"));
        double total=charged(SummaryEvidenceEvaluationMain.V17_RESULTS);assertTrue(total>.21034575&&total<3);assertFalse(canAfford(2.9,reserveUpper("{}"),3));
    }
    @Test void saved114ResultsAreCompletedUntruncatedAndNeverRetried() throws Exception {
        var all=rows();assertEquals(114,all.size());var fingerprints=new HashSet<String>();
        for(var r:all){assertTrue(fingerprints.add(r.path("requestSha256").asText()));assertEquals("completed",r.path("providerStatus").asText());assertTrue(r.path("usage").path("output_tokens").asInt()<OUTPUT_LIMIT);}
    }
    @Test void existingLedgerReuseChecksFullRequestNotOnlyDirectoryOrInputHash() throws Exception {
        var r=rows().getFirst();var folder=SummaryEvidenceEvaluationMain.V17_RESULTS.resolve(r.path("requestSha256").asText());
        String exact=canonical(request(r));verifyCachedRequest(folder,exact);
        assertThrows(IllegalStateException.class,()->verifyCachedRequest(folder,exact.replace("12000","11999")));
    }
}
