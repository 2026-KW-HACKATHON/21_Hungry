package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.SourceText;
import com.kw.knowone.encounter.processing.AnalysisOutputValidator;

class AnalysisOutputValidatorTests {
    private final ObjectMapper json=new ObjectMapper();private final AnalysisOutputValidator validator=new AnalysisOutputValidator(json);
    private final String source=UUID.randomUUID().toString();

    @Test void unicodeOffsetsUseCodePointsAndRepeatedQuoteOccurrence(){String raw=output("약😀",source,1,1,taskPayload());
        var result=validator.validate(raw,input("약😀 약😀",LocalDate.of(2026,10,7)),"TEST","model","1.0",1,1);
        var evidence=json.readTree(result.items().getFirst().evidenceJson()).get(0);assertEquals(3,evidence.get("start").asInt());assertEquals(5,evidence.get("end").asInt());assertEquals("READY",result.items().getFirst().reviewState());}

    @Test void invalidEvidenceCannotAutoApply(){String raw=output("없는 인용",UUID.randomUUID().toString(),1,0,taskPayload());
        var result=validator.validate(raw,input("원문",LocalDate.of(2026,10,7)),"TEST","model","1.0",0,0);
        assertEquals("NEEDS_REVIEW",result.items().getFirst().reviewState());assertTrue(result.items().getFirst().reviewReasons().contains("INVALID_EVIDENCE"));}

    @Test void medicationWithUnknownTimeAndNullFactsStaysForReview(){String payload="""
            {"schemaVersion":1,"name":"A약","doseText":"0.5정","frequencyText":"하루 두 번","startsOn":"2026-10-08","endsOn":null,"instructions":null,"supersedesMedicationId":null,"schedulePlans":[]}
            """;String raw=output("0.5정",source,1,0,payload);var result=validator.validate(raw,input("0.5정",LocalDate.of(2026,10,7)),"TEST","model","1.0",0,0);
        assertEquals("NEEDS_REVIEW",result.items().getFirst().reviewState());assertTrue(result.items().getFirst().reviewReasons().contains("MISSING_ENDSON"));assertTrue(result.items().getFirst().reviewReasons().contains("MISSING_TIME"));}

    @Test void relativeDateWithoutEncounterDateIsNotReady(){String raw=output("일주일 뒤 검사",source,1,0,taskPayload());var result=validator.validate(raw,input("일주일 뒤 검사",null),"TEST","model","1.0",0,0);
        assertTrue(result.items().getFirst().reviewReasons().contains("RELATIVE_DATE_WITHOUT_BASE"));}

    @Test void relativeDateMustMatchEncounterDate(){String wrong=output("일주일 뒤 검사",source,1,0,taskPayload());var wrongResult=validator.validate(wrong,input("일주일 뒤 검사",LocalDate.of(2026,10,2)),"TEST","model","1.0",0,0);
        assertTrue(wrongResult.items().getFirst().reviewReasons().contains("RELATIVE_DATE_MISMATCH"));String correct=wrong.replace("2026-10-08","2026-10-08");var correctResult=validator.validate(correct,input("일주일 뒤 검사",LocalDate.of(2026,10,1)),"TEST","model","1.0",0,0);assertFalse(correctResult.items().getFirst().reviewReasons().contains("RELATIVE_DATE_MISMATCH"));}

    @Test void modelCannotChooseSupersededDatabaseId(){String payload="""
            {"schemaVersion":1,"name":"A약","doseText":"0.5정","frequencyText":"하루 1회","startsOn":"2026-10-08","endsOn":"2026-10-09","instructions":"1.5mg","supersedesMedicationId":"11111111-1111-4111-8111-111111111111","schedulePlans":[{"recurrence":"DAILY","firstDate":"2026-10-08","lastDate":"2026-10-09","weekdays":[],"localTime":"08:00","durationMinutes":30}]}
            """;var result=validator.validate(output("0.5정",source,1,0,payload),input("A약 0.5정 1.5mg",LocalDate.of(2026,10,7)),"TEST","model","1.0",0,0);JsonNode stored=json.readTree(result.items().getFirst().payloadJson());assertTrue(stored.get("supersedesMedicationId").isNull());assertEquals("1.5mg",stored.get("instructions").asText());assertTrue(result.items().getFirst().reviewReasons().contains("MODEL_DATABASE_ID_REJECTED"));}

    @Test void conditionalPlanNeverBecomesReady(){String raw=output("필요하면 검사",source,1,0,taskPayload()).replace("\"isConditional\":false","\"isConditional\":true");var result=validator.validate(raw,input("필요하면 검사",LocalDate.of(2026,10,7)),"TEST","model","1.0",0,0);assertEquals("NEEDS_REVIEW",result.items().getFirst().reviewState());assertTrue(result.items().getFirst().reviewReasons().contains("CONDITIONAL_PLAN"));}

    @Test void detailsCannotReferenceMissingEvidence(){String raw=output("검사",source,1,0,taskPayload()).replace("\"symptoms\":[]","\"symptoms\":[{\"text\":\"검사\",\"evidenceIndexes\":[0]}]");assertEquals("AI_EVIDENCE_INVALID",assertThrows(com.kw.knowone.encounter.processing.AiProviderException.class,()->validator.validate(raw,input("검사",LocalDate.of(2026,10,7)),"TEST","model","1.0",0,0)).code());}

    @Test void conflictingAudioAndDocumentMedicationFactsRequireResolution(){String document=UUID.randomUUID().toString();String first="""
            {"schemaVersion":1,"name":"A약","doseText":"0.5정","frequencyText":"하루 1회","startsOn":"2026-10-08","endsOn":"2026-10-09","instructions":null,"supersedesMedicationId":null,"schedulePlans":[{"recurrence":"DAILY","firstDate":"2026-10-08","lastDate":"2026-10-09","weekdays":[],"localTime":"08:00","durationMinutes":30}]}
            """;String second=first.replace("0.5정","1정");String raw="""
            {"schemaVersion":"1.0","summary":null,"details":{"symptoms":[],"tests":[],"medicationMentions":[],"precautions":[],"followUps":[]},"evidence":[],"items":[
            {"itemKey":"audio","itemType":"MEDICATION","payload":%s,"evidence":[{"sourceId":"%s","textVersion":1,"page":null,"quote":"A약 0.5정","occurrenceIndex":0}],"uncertaintyCodes":[],"isConditional":false},
            {"itemKey":"document","itemType":"MEDICATION","payload":%s,"evidence":[{"sourceId":"%s","textVersion":1,"page":1,"quote":"A약 1정","occurrenceIndex":0}],"uncertaintyCodes":[],"isConditional":false}]}
            """.formatted(first,source,second,document);AnalysisInput input=new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-07T00:00:00Z"),"Asia/Seoul",List.of(new SourceText(source,1,"AUDIO","audio/wav","A약 0.5정"),new SourceText(document,1,"DOCUMENT","application/pdf","A약 1정")),List.of());var result=validator.validate(raw,input,"TEST","model","1.0",0,0);assertEquals(2,result.items().stream().filter(v->v.reviewReasons().contains("CONFLICT")).count());}

    private AnalysisInput input(String text,LocalDate occurred){return new AnalysisInput(occurred,Instant.parse("2026-10-07T00:00:00Z"),"Asia/Seoul",List.of(new SourceText(source,1,"AUDIO","audio/wav",text)),List.of());}
    private String taskPayload(){return """
            {"schemaVersion":1,"kind":"EXAM","title":"검사","description":null,"date":"2026-10-08","time":"10:00","durationMinutes":30,"durationSource":"SOURCE","recurrence":"ONCE","lastDate":"2026-10-08","weekdays":[]}
            """;}
    private String output(String quote,String evidenceSource,int version,int occurrence,String payload){String type=payload.contains("\"name\"")?"MEDICATION":"TASK";return """
            {"schemaVersion":"1.0","summary":null,"details":{"symptoms":[],"tests":[],"medicationMentions":[],"precautions":[],"followUps":[]},"evidence":[],"items":[{"itemKey":"one","itemType":"%s","payload":%s,"evidence":[{"sourceId":"%s","textVersion":%d,"page":null,"quote":%s,"occurrenceIndex":%d}],"uncertaintyCodes":[],"isConditional":false}]}
            """.formatted(type,payload,evidenceSource,version,json.writeValueAsString(quote),occurrence);}
}
