package com.kw.knowone.encounter.processing;

import com.kw.knowone.encounter.processing.AiProcessingPort.*;
import java.time.*;
import java.net.http.HttpClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticAnalysisTests {
    final ObjectMapper json=new ObjectMapper();
    final AnalysisInput input=new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-09T00:00:00Z"),"Asia/Seoul",
        List.of(new SourceText("00000000-0000-4000-8000-000000000001",1,"AUDIO","audio/wav","의사: 오른쪽이 아픈가요?\n환자: 아니요.\n의사: 증상이 계속되면 오세요.\n의사: 가상약은 한 알씩 하루 두 번, 나흘 복용하세요.")),List.of());
    final UtteranceEvidence catalog=new UtteranceEvidence(json,input);
    final List<String> ids=catalog.spans().stream().map(UtteranceEvidence.Span::id).toList();
    final SemanticAnalysis semantic=new SemanticAnalysis(json,catalog);
    ObjectNode fact(String kind) {
        var f=json.createObjectNode().put("factId","f1");var support=f.putObject("support");
        support.putArray("statementIds").add(ids.get(2));support.putArray("questionIds");support.putArray("answerIds");support.putArray("correctionIds");
        f.put("kind",kind).put("text","증상이 계속되면 재방문");f.putObject("condition").put("operator","NONE").putArray("clauses");
        f.put("medicationRole","NONE").putNull("alternativeGroup").put("actionStatus","NONE").putNull("medication").putNull("task").putArray("uncertaintyCodes");return f;
    }
    ObjectNode med() {
        var f=fact("MEDICATION");f.put("text","한 알씩 하루 두 번, 나흘 복용").put("medicationRole","PRESCRIBED");
        ((ObjectNode)f.get("support")).putArray("statementIds").add(ids.get(3));
        var m=f.putObject("medication").putNull("name").put("doseText","한 알").put("frequencyText","하루 두 번").put("courseDurationText","나흘").putNull("instructions").putNull("startsOn").putNull("endsOn").putNull("scheduleRecurrence");m.putArray("clockTimes");m.putArray("weekdays");return f;
    }
    ObjectNode future() {
        var f=fact("FOLLOW_UP");f.put("text","재방문").put("actionStatus","FUTURE");
        f.putObject("condition").put("operator","ALL").putArray("clauses").add("증상 지속");
        var t=f.putObject("task").put("kind","HOSPITAL").put("title","재방문").putNull("date").putNull("time").put("recurrence","ONCE").putNull("lastDate");t.putArray("weekdays");return f;
    }
    ObjectNode response(ObjectNode... facts) {
        var root=json.createObjectNode().put("schemaVersion","1.8");var fs=root.putArray("facts");var summary=root.putArray("summaryFactIds");
        for(var fact:facts){fs.add(fact);summary.add(fact.path("factId").asText());}return root;
    }
    AnalysisResult parse(ObjectNode root) {return new AnalysisOutputValidator(json).validate(semantic.resolve(root.toString()),input,"OPENAI","test","1.8",0,0);}
    @Test void questionAndAnswerResolveTogetherWithoutInventedPosition() {
        var f=fact("SYMPTOM");f.put("text","오른쪽 통증 없음");var s=(ObjectNode)f.get("support");s.putArray("statementIds");s.putArray("questionIds").add(ids.get(0));s.putArray("answerIds").add(ids.get(1));
        var result=parse(response(f));assertEquals(2,json.readTree(result.evidenceJson()).size());
    }
    @Test void questionOnlyAndBareAnswerAreRejectedNotDropped() {
        var f=fact("SYMPTOM");var s=(ObjectNode)f.get("support");s.putArray("statementIds");s.putArray("questionIds").add(ids.get(0));
        assertEquals("AI_EVIDENCE_INVALID",assertThrows(AiProviderException.class,()->parse(response(f))).code());
        s.putArray("questionIds");s.putArray("statementIds").add(ids.get(1));
        assertThrows(AiProviderException.class,()->parse(response(f)));
    }
    @Test void periodAndFrequencyNeverConstructClockOrDuration() {
        var result=parse(response(med()));var p=json.readTree(result.items().getFirst().payloadJson());
        assertTrue(p.path("schedulePlans").isEmpty());assertTrue(p.path("instructions").asText().contains("나흘"));assertTrue(p.path("startsOn").isNull());
    }
    @Test void conditionalActionWithUnknownDateIsRetainedForReview() {
        var result=parse(response(future()));assertEquals(1,result.items().size());var i=result.items().getFirst();
        assertEquals("NEEDS_REVIEW",i.reviewState());assertTrue(i.reviewReasons().containsAll(List.of("CONDITIONAL_PLAN","MISSING_SCHEDULE")));
        var p=json.readTree(i.payloadJson());assertTrue(p.path("date").isNull());assertEquals(120,p.path("durationMinutes").asInt());assertEquals("PLANNING_DEFAULT",p.path("durationSource").asText());assertFalse(result.summary().contains("120"));
    }
    @Test void unknownOtherPlanningTimeStaysNull() {
        var f=future();((ObjectNode)f.get("task")).put("kind","OTHER");var p=json.readTree(parse(response(f)).items().getFirst().payloadJson());
        assertTrue(p.path("durationMinutes").isNull());assertTrue(p.path("durationSource").isNull());
    }
    @Test void alternativeIsOneConditionalUnnamedReviewCandidate() {
        var f=med();f.put("medicationRole","ALTERNATIVE").put("alternativeGroup","choice-a");
        var result=parse(response(f));assertEquals(1,result.items().size());assertTrue(result.items().getFirst().reviewReasons().contains("MEDICATION_CHOICE_REQUIRED"));
        var second=(ObjectNode)f.deepCopy();second.put("factId","f2");assertThrows(AiProviderException.class,()->parse(response(f,second)));
    }
    @Test void examplesHistoryAndSupersededRemainVisibleNotPrescriptions() {
        for(String role:List.of("EXAMPLE","HISTORICAL","SUPERSEDED")) {
            var f=med();f.put("medicationRole",role);var result=parse(response(f));assertTrue(result.items().isEmpty());assertFalse(json.readTree(result.detailsJson()).path("medicationMentions").isEmpty());
        }
    }
    @Test void clinicianOnlyOrCompletedActionIsNotFamilyTask() {
        for(String status:List.of("CLINICIAN_ONLY","IN_PROGRESS","COMPLETED")) {
            var f=future();f.put("actionStatus",status).putNull("task");assertTrue(parse(response(f)).items().isEmpty());
        }
    }
    @Test void missingFutureCandidateIsRejectedRatherThanHidden() {
        var f=future();f.putNull("task");assertEquals("AI_SEMANTIC_INVALID",assertThrows(AiProviderException.class,()->parse(response(f))).code());
    }
    @Test void sameConditionIsUsedInSummaryDetailsAndCandidate() {
        for(String operator:List.of("ALL","ANY")) {
            var f=future();f.putObject("condition").put("operator",operator).putArray("clauses").add("열").add("기운 없음");
            var result=parse(response(f));String detail=json.readTree(result.detailsJson()).path("followUps").get(0).path("text").asText();
            assertEquals(result.summary(),detail);assertEquals(detail,json.readTree(result.items().getFirst().payloadJson()).path("description").asText());
            assertTrue(detail.contains(operator.equals("ALL")?"모두":"하나라도"));
        }
    }
    @Test void unknownRefsAndContradictoryConditionFailClosed() {
        var root=response(fact("SYMPTOM"));root.putArray("summaryFactIds").add("unknown");assertThrows(AiProviderException.class,()->parse(root));
        var f=future();((ObjectNode)f.get("condition")).put("operator","NONE");assertThrows(AiProviderException.class,()->parse(response(f)));
    }
    @Test void actualAdapterRequestUsesInternalFactsNotUnusedPrompt() throws Exception {
        var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"https://api.openai.com/v1","not-a-key","test","test","gpt-5.4-mini-2026-03-17",Duration.ofSeconds(90),12000,HttpClient.newHttpClient(),SemanticAnalysis.PROTOCOL,"1.8");
        var prompt=SummaryEvaluationMain.read(java.nio.file.Path.of("src/main/resources/ai/analysis-prompt-v1.8.txt"));
        var request=json.valueToTree(adapter.analysisRequest(input,prompt));
        assertEquals(prompt,request.at("/input/0/content/0/text").asText());assertTrue(request.at("/text/format/schema/properties/facts").isObject());
        assertTrue(request.at("/text/format/schema/properties/items").isMissingNode());
        var provider=json.createObjectNode().put("status","completed");provider.putArray("output").addObject().putArray("content").addObject().put("type","output_text").put("text",response(future()).toString());
        assertEquals("NEEDS_REVIEW",adapter.parseAnalysis(provider,input,"1.8").items().getFirst().reviewState());
    }
    @Test void twelveRegressionCasesIncludeAllSixRelationsAndParaphrases() throws Exception {
        assertEquals(12,SummaryEvidenceEvaluationMain.semanticCases().size());
    }
}
