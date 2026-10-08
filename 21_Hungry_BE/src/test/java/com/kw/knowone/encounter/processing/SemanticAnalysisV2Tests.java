package com.kw.knowone.encounter.processing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class SemanticAnalysisV2Tests extends SemanticAnalysisTests {
    ObjectNode v2(ObjectNode... facts){return response(facts).put("schemaVersion","1.8.1");}
    SemanticAnalysisV2 revised(){return new SemanticAnalysisV2(json,catalog);}
    @Test void adjacentQuestionIsAddedWithAuditTrailNotInventedAnswer() {
        var f=fact("SYMPTOM");f.put("text","오른쪽 통증 없음");
        ((ObjectNode)f.path("support")).putArray("statementIds").add(ids.get(1));
        var audit=revised().inspect(v2(f).toString());assertTrue(audit.failures().isEmpty());
        assertEquals(ids.get(0),audit.repairs().getFirst().utteranceId());
        assertEquals(2,json.readTree(revised().resolve(v2(f).toString())).path("evidence").size());
    }
    @Test void contextAlreadySelectedInStatementArrayDoesNotFailOnRoleLabels() {
        var f=fact("SYMPTOM");((ObjectNode)f.path("support")).putArray("statementIds").add(ids.get(0)).add(ids.get(1));
        assertTrue(revised().inspect(v2(f).toString()).failures().isEmpty());
    }
    @Test void negativeMedicationFindingNeedsNoFictionalDrugObject() {
        var f=fact("MEDICATION");f.put("text","다른 약 복용 없음");
        var resolved=json.readTree(revised().resolve(v2(f).toString()));
        assertTrue(resolved.path("items").isEmpty());assertEquals(1,resolved.at("/details/medicationMentions").size());
    }
    @Test void medicationFutureDoesNotRequireDuplicateGeneralTask() {
        var f=med();f.put("actionStatus","FUTURE");
        var out=json.readTree(revised().resolve(v2(f).toString()));assertEquals(1,out.path("items").size());
        assertEquals("MEDICATION",out.path("items").get(0).path("itemType").asText());
    }
    @Test void actionContentNotAssessmentLabelDeterminesTaskEligibility() {
        var f=future();f.put("kind","ASSESSMENT");assertDoesNotThrow(()->revised().resolve(v2(f).toString()));
    }
    @Test void missingConditionAndInventedDailyRemainBlocked() {
        var f=med();f.put("medicationRole","CONDITIONAL");
        assertEquals("AI_CONDITION_MISSING",revised().inspect(v2(f).toString()).failures().getFirst().code());
        var m=med();((ObjectNode)m.path("medication")).put("scheduleRecurrence","DAILY");
        assertFalse(revised().inspect(v2(m).toString()).failures().isEmpty());
    }
    @Test void failuresAreExplicitAndWholeApplicationIsBlocked() {
        var bad=future();bad.put("factId","bad").putNull("task");var good=med();
        var audit=revised().inspect(v2(good,bad).toString());assertEquals(2,audit.normalized().path("facts").size());
        assertEquals("bad",audit.failures().getFirst().factId());
        assertThrows(AiProviderException.class,()->revised().resolve(v2(good,bad).toString()));
    }
    @Test void questionsAloneAndUnknownIdsStillFail() {
        var f=fact("SYMPTOM");((ObjectNode)f.path("support")).putArray("statementIds").add(ids.get(0));
        assertFalse(revised().inspect(v2(f).toString()).failures().isEmpty());
        ((ObjectNode)f.path("support")).putArray("statementIds").add("unknown");
        assertFalse(revised().inspect(v2(f).toString()).failures().isEmpty());
    }
    @Test void medicationObjectHasAuthoritativeCategoryWithVisibleRepair() {
        var f=med();f.put("kind","ASSESSMENT").put("medicationRole","HISTORICAL");
        var audit=revised().inspect(v2(f).toString());assertTrue(audit.failures().isEmpty());
        assertEquals("CATEGORY_FROM_MEDICATION_OBJECT",audit.repairs().getFirst().code());
        assertTrue(json.readTree(revised().resolve(v2(f).toString())).path("items").isEmpty());
    }
    @Test void mixedFindingAndQuestionIsNotQuestionOnly() {
        var source=new AiProcessingPort.SourceText("00000000-0000-4000-8000-000000000002",1,"AUDIO","audio/wav","의사: 부기는 줄었습니다. 통증은 있나요?");
        var other=new AiProcessingPort.AnalysisInput(input.occurredOn(),input.analyzedAt(),input.timezone(),java.util.List.of(source),java.util.List.of());
        var evidence=new UtteranceEvidence(json,other);var f=fact("ASSESSMENT");f.put("text","부기가 줄었음");
        ((ObjectNode)f.path("support")).putArray("statementIds").add(evidence.spans().iterator().next().id());
        assertTrue(new SemanticAnalysisV2(json,evidence).inspect(v2(f).toString()).failures().isEmpty());
    }
}
