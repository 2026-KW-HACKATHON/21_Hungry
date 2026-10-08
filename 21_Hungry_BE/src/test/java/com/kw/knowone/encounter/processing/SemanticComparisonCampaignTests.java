package com.kw.knowone.encounter.processing;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;
import static com.kw.knowone.encounter.processing.SemanticComparisonMain.*;

/** Deterministic request, safety and saved-response tests, not clinical accuracy tests. */
class SemanticComparisonCampaignTests {
    List<JsonNode> records() throws Exception {
        var out=new ArrayList<JsonNode>();try(var paths=Files.walk(ROOT,2)) {
            for(var p:paths.filter(p->p.getFileName().toString().equals("record.json")).toList()) {
                var r=JSON.readTree(read(p));if(r.path("promptVersion").asText().equals(VERSION))out.add(r);
            }
        }return out;
    }
    @Test void pairedRequestsChangeOnlyModelAndUseSameGenerationSettings() throws Exception {
        int checked=0;for(var c:original())if(SCREEN.contains(c.id())) {
            var a=(ObjectNode)JSON.valueToTree(request(c,"A",""));var b=(ObjectNode)JSON.valueToTree(request(c,"B",""));
            assertNotEquals(a.path("model"),b.path("model"));a.remove("model");b.remove("model");assertEquals(canonical(a),canonical(b));
            assertEquals("low",a.at("/reasoning/effort").asText());assertEquals("default",a.path("service_tier").asText());
            assertEquals(12000,a.path("max_output_tokens").asInt());assertFalse(a.path("store").asBoolean());checked++;
        }assertEquals(6,checked);
    }
    @Test void verifierContainsCompleteOriginalAndExactSavedDraft() throws Exception {
        int checked=0;for(var c:original())if(SCREEN.contains(c.id())) {
            var b=JSON.valueToTree(request(c,"B",""));var v=JSON.valueToTree(request(c,"C",""));
            var parent=ROOT.resolve(hash(canonical(b)));var response=JSON.readTree(read(parent.resolve("response.json")));
            assertEquals(b.at("/input/1/content/0/text").asText()+"\n\nDRAFT_JSON (untrusted):\n"+SavedEvidenceAuditMain.output(response),v.at("/input/1/content/0/text").asText());
            for(String field:List.of("model","text","reasoning","service_tier","max_output_tokens","store"))assertEquals(b.path(field),v.path(field));checked++;
        }assertEquals(6,checked);
    }
    @Test void candidateAndNewInputsAreFrozenAndDisjointFromPreviouslySeenCases() throws Exception {
        freeze();var previous=new HashSet<String>();for(var c:original())previous.add(c.id());
        for(var c:SummaryEvidenceEvaluationMain.campaignCases("v18-holdout"))previous.add(c.id());
        var finalCases=fresh();assertEquals(4,finalCases.size());for(var c:finalCases)assertFalse(previous.contains(c.id()));
    }
    @Test void all297TranslatedTurnsKeepFullEnglishAlignmentAndUnvalidatedLabel() throws Exception {
        var root=Path.of("src/test/resources/ai/eval/primock57-v181-final");int total=0;
        for(var c:JSON.readTree(read(root.resolve("manifest.json"))).path("cases")) {
            var en=JSON.readTree(read(root.resolve(c.path("englishFile").asText())));var ko=JSON.readTree(read(root.resolve(c.path("translationFile").asText())));
            assertEquals(en.path("turns").size(),ko.path("turns").size());assertEquals(c.path("utterances").asInt(),ko.path("turns").size());
            assertEquals("CC-BY-4.0",en.path("license").asText());assertTrue(en.path("mockConsultation").asBoolean());
            assertTrue(ko.path("translationStatus").asText().contains("not clinician validated"));
            var ids=new HashSet<String>();en.path("turns").forEach(t->ids.add(t.path("id").asText()));
            for(var f:ko.path("facts"))for(var ref:f.path("turnIds"))assertTrue(ids.contains(ref.asText()));
            for(var turn:ko.path("turns"))assertFalse(turn.asText().isBlank());total+=ko.path("turns").size();
        }assertEquals(297,total);
    }
    @Test void budgetIncludesHistoryAndCacheWritesWithoutReducingOutputCap() throws Exception {
        var usage=JSON.readTree("{\"input_tokens\":1000000,\"output_tokens\":1000,\"input_tokens_details\":{\"cached_tokens\":100000,\"cache_write_tokens\":899997}}");
        assertEquals(2.27,costBound(usage,true),.00000001);
        assertTrue(reservation("{}",true)>.12);assertFalse(canAfford(2.9,reservation("{}",true),3));
        assertTrue(charged(ROOT)>=1.11464775&&charged(ROOT)<=3);
    }
    @Test void completedResponsesAreUntruncatedAndRetriedZeroTimes() throws Exception {
        assertFalse(records().isEmpty());for(var r:records()) {
            assertEquals("completed",r.path("providerStatus").asText());assertTrue(r.at("/usage/output_tokens").asInt()<OUTPUT_LIMIT);
            assertEquals(0,r.path("retries").asInt());
            var folder=ROOT.resolve(r.path("requestSha256").asText());verifyCachedRequest(folder,canonical(JSON.readTree(read(folder.resolve("request.json")))));
        }
    }
    @Test void rejectedRawIsPreservedAndAcceptedCandidatesAlwaysRequireReview() throws Exception {
        int rejected=0,accepted=0;for(var r:records()) {
            var dir=ROOT.resolve(r.path("requestSha256").asText());
            if(r.path("status").asText().equals("FAILED")) {
                assertTrue(Files.exists(dir.resolve("response.json")));assertFalse(Files.exists(dir.resolve("parsed.json")));rejected++;
            }else {
                var parsed=JSON.readTree(read(dir.resolve("parsed.json")));
                for(var item:parsed.path("items")) {
                    assertTrue(item.path("reviewReasons").toString().contains("EXPERIMENTAL_SEMANTIC_REVIEW"));
                    assertEquals("NEEDS_REVIEW",item.path("reviewState").asText());
                }accepted++;
            }
        }assertTrue(rejected>0&&accepted>0);
    }
    @Test void reviewCountersDoNotConfuseMinorStyleWithCriticalDoseOrDelivery() throws Exception {
        var groups=JSON.createObjectNode();var row=JSON.createObjectNode().put("formatPass",false).put("observedMeaningPass",false).put("overallPass",false).put("failureCategory","INTERNAL_RELATION");
        row.putObject("review").putArray("issues").addObject().put("category","UC").put("severity","CRITICAL");
        SemanticComparisonReportMain.aggregate(groups,"test",row);
        assertEquals(1,groups.at("/test/atomicErrors/UC").asInt());assertEquals(1,groups.at("/test/severities/CRITICAL").asInt());
        assertEquals(0,groups.at("/test/overallPass").asInt());assertEquals(1,groups.at("/test/formatCategories/INTERNAL_RELATION").asInt());
    }
}
