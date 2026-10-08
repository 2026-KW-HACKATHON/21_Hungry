package com.kw.knowone.encounter.processing;

import com.kw.knowone.encounter.processing.AiProcessingPort.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

class UtteranceEvidenceTests {
    final ObjectMapper json=new ObjectMapper();
    AnalysisInput input(String text,String media) {
        return new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-09T00:00:00Z"),"Asia/Seoul",
            List.of(new SourceText("00000000-0000-0000-0000-000000000001",1,media.equals("audio/wav")?"AUDIO":"DOCUMENT",media,text)),List.of());
    }
    ObjectNode output(String text,List<String> ids) {
        var out=json.createObjectNode().put("schemaVersion","1.0");
        var claim=out.putArray("summary").addObject().put("text",text);claim.set("utteranceIds",json.valueToTree(ids));
        var details=out.putObject("details");for(String field:UtteranceEvidence.DETAIL_FIELDS)details.putArray(field);
        out.putArray("items");return out;
    }
    AnalysisResult resolve(UtteranceEvidence catalog,ObjectNode output,AnalysisInput input) {
        return new AnalysisOutputValidator(json).validate(catalog.resolve(output.toString()),input,"OPENAI",MODEL,"1.5",1,1);
    }
    @Test void exactUnicodeOffsetsPdfPagesCrLfAndRepeatedText() {
        var input=input("😀첫 줄\r\n의사: <UNIN/> 그대로\f의사: 반복\r\n의사: 반복","application/pdf");
        var catalog=new UtteranceEvidence(json,input);var spans=new ArrayList<>(catalog.spans());
        assertEquals(4,spans.size());assertEquals(2,spans.get(3).page());
        var result=resolve(catalog,output("테스트",spans.stream().map(UtteranceEvidence.Span::id).toList()),input);
        var evidence=json.readTree(result.evidenceJson());assertEquals(1,evidence.get(3).path("occurrenceIndex").asInt());
        for(var e:evidence) {
            String source=input.sources().getFirst().text();
            assertEquals(e.path("quote").asText(),source.substring(source.offsetByCodePoints(0,e.path("start").asInt()),source.offsetByCodePoints(0,e.path("end").asInt())));
        }
        assertTrue(evidence.get(1).path("quote").asText().contains("<UNIN/>"));
    }
    @Test void explicitInlineLabelsOnlyAndVersionedIds() {
        var input=input("환자: 제가 보기엔 감기. 의사: 아직 몰라요.\n화자 미상 발화","audio/wav");
        var catalog=new UtteranceEvidence(json,input);var spans=new ArrayList<>(catalog.spans());
        assertEquals(List.of("PATIENT","CLINICIAN","UNKNOWN"),spans.stream().map(UtteranceEvidence.Span::speaker).toList());
        assertEquals(catalog.context(),new UtteranceEvidence(json,input).context());
        var source=input.sources().getFirst();var changed=new AnalysisInput(input.occurredOn(),input.analyzedAt(),input.timezone(),List.of(new SourceText(source.sourceId(),2,source.sourceType(),source.mediaType(),source.text())),List.of());
        assertNotEquals(spans.getFirst().id(),new UtteranceEvidence(json,changed).spans().iterator().next().id());
    }
    @Test void sourceReorderingDoesNotChangeIds() {
        var a=input("의사: 첫 자료","audio/wav");var b=new SourceText("00000000-0000-0000-0000-000000000002",1,"DOCUMENT","image/png","처방전");
        var forward=new AnalysisInput(a.occurredOn(),a.analyzedAt(),a.timezone(),List.of(a.sources().getFirst(),b),List.of());
        var reverse=new AnalysisInput(a.occurredOn(),a.analyzedAt(),a.timezone(),List.of(b,a.sources().getFirst()),List.of());
        assertEquals(new HashSet<>(new UtteranceEvidence(json,forward).spans()),new HashSet<>(new UtteranceEvidence(json,reverse).spans()));
    }
    @Test void unknownDuplicateAndEmptyEvidenceFailClosed() {
        var input=input("의사: 결과 미확인","audio/wav");var catalog=new UtteranceEvidence(json,input);String id=catalog.spans().iterator().next().id();
        for(var ids:List.of(List.of("missing"),List.of(id,id),List.<String>of()))
            assertEquals("AI_EVIDENCE_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve(output("결과 미확인",ids).toString())).code());
    }
    @Test void invalidItemEvidenceCannotBeSilentlyDroppedToNeedsReview() {
        var input=input("의사: 약 복용","audio/wav");var catalog=new UtteranceEvidence(json,input);var out=output("약 복용",List.of(catalog.spans().iterator().next().id()));
        var item=out.withArray("items").addObject().put("itemKey","m1").put("itemType","MEDICATION").put("isConditional",false);
        item.putObject("payload");item.putArray("uncertaintyCodes");item.putArray("utteranceIds").add("missing");
        assertEquals("AI_EVIDENCE_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve(out.toString())).code());
    }
    @Test void localH01RegressionUsesRealFullTurnNotFabricatedSpeakerPrefix() throws Exception {
        var c=syntheticCases().stream().filter(x->x.id().equals("H01_NOVEL_MIXED_CORRECTION")).findFirst().orElseThrow();
        var catalog=new UtteranceEvidence(json,c.input());var doctor=catalog.spans().stream().filter(s->s.speaker().equals("CLINICIAN")).findFirst().orElseThrow();
        var result=resolve(catalog,output("위염 미확정. 가상 C약 저녁 1정, 4일 복용. 검사 양성이면 예약일 전 연락.",List.of(doctor.id())),c.input());
        String quote=json.readTree(result.evidenceJson()).get(0).path("quote").asText();
        assertTrue(quote.startsWith("의사: 위염이라고"));assertTrue(quote.contains("2정, 정정해서 1정"));
        assertTrue(c.input().sources().getFirst().text().contains(quote));
        // This is a constructed local protocol test, NOT a live-model quality result.
    }
    @Test void detailsIndexesAreServerAssignedAndDeduplicatedAcrossClaims() {
        var input=input("의사: 검사 예정\n환자: 통증 있어요","audio/wav");var catalog=new UtteranceEvidence(json,input);var spans=new ArrayList<>(catalog.spans());
        var out=output("검사 예정",List.of(spans.getFirst().id()));
        var detail=out.withObject("details").withArray("symptoms").addObject().put("text","통증");detail.putArray("utteranceIds").add(spans.get(1).id());
        var test=out.withObject("details").withArray("tests").addObject().put("text","검사 예정");test.putArray("utteranceIds").add(spans.get(0).id());
        var result=resolve(catalog,out,input);
        assertEquals(2,json.readTree(result.evidenceJson()).size());
        assertEquals(0,json.readTree(result.detailsJson()).at("/tests/0/evidenceIndexes/0").asInt());
        assertEquals(1,json.readTree(result.detailsJson()).at("/symptoms/0/evidenceIndexes/0").asInt());
    }
    @Test void realRequestUsesIdEnumAndPreservesExternalPayloadSchema() throws Exception {
        var input=syntheticCases().getFirst().input();
        var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),UtteranceEvidence.PROTOCOL);
        var request=json.valueToTree(adapter.analysisRequest(input,"protocol candidate"));
        var schema=request.at("/text/format/schema");
        assertFalse(schema.path("properties").has("evidence"));assertTrue(schema.at("/$defs/utteranceId/enum").size()>0);
        assertFalse(schema.toString().contains("evidenceIndexes"));assertFalse(schema.toString().contains("occurrenceIndex"));
        assertTrue(request.at("/input/1/content/0/text").asText().contains("utterances"));
        assertEquals(OUTPUT_LIMIT,request.path("max_output_tokens").asInt());
        var legacy=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient());
        var old=json.valueToTree(legacy.analysisRequest(input,"old"));
        assertEquals(old.at("/text/format/schema/$defs/taskPayload"),schema.at("/$defs/taskPayload"));
        assertEquals(old.at("/text/format/schema/$defs/medicationPayload"),schema.at("/$defs/medicationPayload"));
    }
    @Test void existingIdDoesNotProveEntailment() {
        var input=input("의사: 폐렴인가요?\n환자: 그런 것 같아요.\n의사: 검사 전엔 모릅니다.","audio/wav");var catalog=new UtteranceEvidence(json,input);
        var wrong=output("폐렴 확진",List.of(catalog.spans().iterator().next().id()));
        assertNotNull(resolve(catalog,wrong,input));
        // Intentional negative control: structural validity != semantic accuracy. Quality review MUST reject this claim.
    }
    @Test void separateDollarBudgetAndFormerHoldoutDevelopment() throws Exception {
        assertFalse(canAfford(.95,.06,1));assertTrue(canAfford(.8,.19,1));assertFalse(canAfford(Double.NaN,.1,1));
        assertEquals(10,SummaryEvidenceEvaluationMain.campaignCases("public-development").size());
        assertThrows(IllegalArgumentException.class,()->SummaryEvidenceEvaluationMain.campaignCases("holdout"));
    }
    @Test void newHoldoutHasCompleteAlignedTranslationsAndValidFactLinks() throws Exception {
        var data=SummaryEvidenceEvaluationMain.NEW_DATA;
        var entries=json.readTree(read(data.resolve("manifest.json"))).path("cases");assertEquals(2,entries.size());
        var oldIds=new HashSet<>(SummaryEvidenceEvaluationMain.campaignCases("public-development").stream().map(EvalCase::id).toList());
        int total=0;
        for(var entry:entries) {
            assertFalse(oldIds.contains(entry.path("id").asText()));
            var en=json.readTree(read(data.resolve(entry.path("englishFile").asText())));
            var ko=json.readTree(read(data.resolve(entry.path("translationFile").asText())));
            assertEquals(en.path("turns").size(),ko.path("turns").size());total+=en.path("turns").size();
            assertTrue(ko.path("translationStatus").asText().contains("not clinician validated"));
            var ids=new HashSet<String>();en.path("turns").forEach(t->ids.add(t.path("id").asText()));
            for(var fact:ko.path("facts"))for(var id:fact.path("turnIds"))assertTrue(ids.contains(id.asText()));
            for(var turn:ko.path("turns"))assertFalse(turn.asText().isBlank());
        }
        assertEquals(239,total);
    }
    @Test void optInAnalyzeUsesSameTransportAndResolverWithoutChangingExternalResult() throws Exception {
        var input=input("의사: 검사 결과는 아직 모릅니다.","audio/wav");
        var catalog=new UtteranceEvidence(json,input);var model=output("검사 결과 미확인.",List.of(catalog.spans().iterator().next().id()));
        var response=json.createObjectNode().put("status","completed");
        response.putArray("output").addObject().putArray("content").addObject().put("type","output_text").put("text",model.toString());
        var captured=new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            captured.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            byte[] body=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fake-test-key","unused","unused",MODEL,Duration.ofSeconds(2),OUTPUT_LIMIT,HttpClient.newHttpClient(),UtteranceEvidence.PROTOCOL);
            var result=adapter.analyze(input);assertEquals("1.5",result.promptVersion());assertEquals("검사 결과 미확인.",result.summary());
            assertEquals(input.sources().getFirst().text(),json.readTree(result.evidenceJson()).get(0).path("quote").asText());
            assertTrue(captured.get().at("/input/0/content/0/text").asText().contains("Protocol: utterance-id-v1"));
            assertFalse(captured.get().at("/text/format/schema").path("properties").has("evidence"));
        } finally {server.stop(0);}
    }
    @Test void groundedProtocolSelectsIdsBeforeClaimsAndRejectsInvalidDuration() throws Exception {
        var input=input("의사: 약 복용","audio/wav");var catalog=new UtteranceEvidence(json,input);
        var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),UtteranceEvidence.GROUNDED_PROTOCOL);
        var request=json.valueToTree(adapter.analysisRequest(input,"candidate"));var schema=request.at("/text/format/schema");
        assertEquals("utteranceIds",schema.at("/$defs/claim/properties").properties().iterator().next().getKey());
        assertEquals("utteranceIds",schema.at("/properties/items/items/properties").properties().iterator().next().getKey());
        assertEquals(1,schema.at("/$defs/medicationPayload/properties/schedulePlans/items/properties/durationMinutes/minimum").asInt());
        var out=output("약 복용",List.of(catalog.spans().iterator().next().id()));
        var item=out.withArray("items").addObject().put("itemKey","m1").put("itemType","MEDICATION").put("isConditional",false);
        item.putArray("uncertaintyCodes");item.putArray("utteranceIds").add(catalog.spans().iterator().next().id());
        var plan=item.putObject("payload").putArray("schedulePlans").addObject().put("durationMinutes",0);
        assertEquals("AI_SCHEMA_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve(out.toString(),true)).code());
        plan.put("durationMinutes",1441);
        assertEquals("AI_SCHEMA_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve(out.toString(),true)).code());
        plan.put("durationMinutes",30);assertNotNull(catalog.resolve(out.toString(),true));
    }
    @Test void savedV13H01MustStillFailTheUnrelaxedLegacyValidator() throws Exception {
        try(var paths=java.nio.file.Files.walk(SummaryEvaluationMain.RESULTS,2)) {
            var record=paths.filter(p->p.getFileName().toString().equals("record.json")).filter(p->{
                try {var r=json.readTree(read(p));return r.path("caseId").asText().equals("H01_NOVEL_MIXED_CORRECTION")&&r.path("promptVersion").asText().equals("1.3");}
                catch(Exception e){throw new IllegalStateException(e);}
            }).findFirst().orElseThrow();
            var input=SavedEvidenceAuditMain.inputFromRequest(json.readTree(read(record.getParent().resolve("request.json"))));
            var response=json.readTree(read(record.getParent().resolve("response.json")));
            var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient());
            assertEquals("AI_EVIDENCE_INVALID",assertThrows(AiProviderException.class,()->adapter.parseAnalysis(response,input,"1.3")).code());
        }
    }
    @Test void malformedJsonExtraFieldsAndOversizedCatalogAreRejected() {
        var input=input("의사: 결과 미확인","audio/wav");var catalog=new UtteranceEvidence(json,input);
        assertEquals("AI_SCHEMA_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve("{bad json")).code());
        var out=output("결과 미확인",List.of(catalog.spans().iterator().next().id()));out.putArray("evidence");
        assertEquals("AI_SCHEMA_INVALID",assertThrows(AiProviderException.class,()->catalog.resolve(out.toString())).code());
        assertEquals("AI_INPUT_LIMIT_EXCEEDED",assertThrows(AiProviderException.class,()->new UtteranceEvidence(json,input("의사: 발화\n".repeat(1001),"audio/wav"))).code());
    }
}
