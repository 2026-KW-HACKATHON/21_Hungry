package com.kw.knowone.encounter.processing;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.kw.knowone.encounter.processing.SummaryEvaluationMain.*;

/** Offline provenance/transport checks, NOT a model quality or clinical validation suite. */
class ReviewedPromptExamplesTests {
    final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
    private final UtteranceEvidenceTests fixtures=new UtteranceEvidenceTests();
    AiProcessingPort.AnalysisInput input(String text,String media) {return fixtures.input(text,media);}
    tools.jackson.databind.node.ObjectNode output(String text,List<String> ids) {return fixtures.output(text,ids);}
    static final Path EXAMPLES=Path.of("src/test/resources/ai/eval/reviewed-prompt-examples-v1.0.0.json");
    String prompt() throws Exception {return read(Path.of("src/main/resources/ai/analysis-prompt-v1.7.txt"));}

    @Test void examplesMatchAcquiredEnglishTranslationAndStoredModelClaims() throws Exception {
        var manifest=json.readTree(read(EXAMPLES));
        assertEquals("CC-BY-4.0",manifest.path("license").asText());
        assertEquals(2,manifest.path("examples").size());
        assertTrue(manifest.path("mockConsultations").asBoolean());
        for(var example:manifest.path("examples")) {
            String enText=read(Path.of(example.path("englishFile").asText()));
            String koText=read(Path.of(example.path("translationFile").asText()));
            // Provenance hashes cover exact UTF-8 file bytes, including a BOM if present.
            assertEquals(example.path("englishSha256").asText(),hash(java.nio.file.Files.readString(Path.of(example.path("englishFile").asText()),StandardCharsets.UTF_8)));
            assertEquals(example.path("translationSha256").asText(),hash(java.nio.file.Files.readString(Path.of(example.path("translationFile").asText()),StandardCharsets.UTF_8)));
            var en=json.readTree(enText);var ko=json.readTree(koText);
            assertEquals(manifest.path("commit"),en.path("commit"));
            assertTrue(example.path("reviewStatus").asText().contains("NOT_CLINICIAN_VALIDATED"));
            for(var turn:example.path("turns")) {
                int index=Integer.parseInt(turn.path("id").asText())-1;
                assertEquals(turn.path("id"),en.path("turns").get(index).path("id"));
                assertEquals(turn.path("speaker"),en.path("turns").get(index).path("speaker"));
                assertEquals(turn.path("en"),en.path("turns").get(index).path("en"));
                assertEquals(turn.path("ko"),ko.path("turns").get(index));
                String grid=read(DATA.resolve("original/"+example.path("caseId").asText()+"_"+turn.path("speaker").asText()+".TextGrid"));
                assertTrue(grid.contains(turn.path("en").asText().replace("\"","\"\"")));
            }
            var folder=Path.of(manifest.path("savedResultsRoot").asText()).resolve(example.path("savedRequest").asText());
            var record=json.readTree(read(folder.resolve("record.json")));
            assertEquals(example.path("caseId"),record.path("caseId"));
            assertEquals(example.path("savedPrompt"),record.path("promptVersion"));
            var response=json.readTree(read(folder.resolve("response.json")));
            JsonNode model=null;
            for(var output:response.path("output"))for(var content:output.path("content"))
                if(content.path("type").asText().equals("output_text"))model=json.readTree(content.path("text").asText());
            assertNotNull(model);
            assertEquals(example.path("savedText"),model.at(example.path("savedClaimPath").asText()+"/text"));
            assertTrue(example.path("changes").size()>0);
        }
    }

    @Test void onlyReviewedExcerptsAndClaimWordingAreInCandidatePrompt() throws Exception {
        var manifest=json.readTree(read(EXAMPLES));String candidate=prompt();
        assertTrue(candidate.contains("NOT complete responses"));
        assertFalse(candidate.contains("JKMS"));assertFalse(candidate.contains("PhysioNet"));
        assertFalse(candidate.contains("## 10. 변경 이력"));
        assertEquals(2,candidate.lines().filter(l->l.startsWith("EXAMPLE ")).count());
        for(var example:manifest.path("examples")) {
            String part=candidate.split("EXAMPLE "+example.path("id").asText()+"\n",2)[1].split("EXAMPLE ",2)[0];
            var fragments=part.lines().filter(l->l.startsWith("{")).map(json::readTree).toList();
            var turns=example.path("turns");assertEquals(turns.size()+1,fragments.size());
            for(int i=0;i<turns.size();i++) {
                assertEquals(turns.get(i).path("ko"),fragments.get(i).path("text"));
                assertEquals(turns.get(i).path("speaker").asText().toUpperCase(Locale.ROOT),fragments.get(i).path("speaker").asText());
            }
            var claim=fragments.getLast();assertEquals(example.path("summary"),claim.path("text"));
            var support=example.has("supportingTurnIds")?example.path("supportingTurnIds"):example.path("turnIds");
            assertEquals(support.size(),claim.path("utteranceIds").size());
            for(int i=0;i<support.size();i++)assertEquals("example-"+example.path("id").asText().toLowerCase(Locale.ROOT)+"-"+support.get(i).asText(),claim.path("utteranceIds").get(i).asText());
        }
    }

    @Test void explicitCandidateIsSentThroughRealAnalyzeRequestAndExistingParser() throws Exception {
        var input=input("의사: 검사 결과는 아직 모릅니다.","audio/wav");
        var model=output("검사 결과 미확인.",List.of(new UtteranceEvidence(json,input).spans().iterator().next().id()));
        var response=json.createObjectNode().put("status","completed");
        response.putArray("output").addObject().putArray("content").addObject().put("type","output_text").put("text",model.toString());
        var captured=new AtomicReference<JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            captured.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            // Uses the same public constructor that Spring configures, without starting Spring or a DB.
            var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fake-test-key","unused","unused",MODEL,Duration.ofSeconds(2),OUTPUT_LIMIT,UtteranceEvidence.GROUNDED_PROTOCOL,"1.7");
            var result=adapter.analyze(input);
            assertEquals("1.7",result.promptVersion());assertEquals("검사 결과 미확인.",result.summary());
            assertEquals(prompt(),captured.get().at("/input/0/content/0/text").asText());
            assertEquals(MODEL,captured.get().path("model").asText());
            assertEquals(OUTPUT_LIMIT,captured.get().path("max_output_tokens").asInt());
            assertFalse(captured.get().path("store").asBoolean());
            assertEquals(input.sources().getFirst().text(),json.readTree(result.evidenceJson()).get(0).path("quote").asText());
            assertFalse(captured.get().at("/text/format/schema").toString().contains("example-a-090"));
            var unchanged=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fake-test-key","unused","unused",MODEL,Duration.ofSeconds(2),OUTPUT_LIMIT,UtteranceEvidence.GROUNDED_PROTOCOL,"");
            assertEquals("1.6",unchanged.analyze(input).promptVersion());
            assertEquals(read(Path.of("src/main/resources/ai/analysis-prompt-v1.6.txt")),captured.get().at("/input/0/content/0/text").asText());
        } finally {server.stop(0);}
    }

    @Test void incompatiblePromptProtocolFailsClosed() {
        for(String protocol:List.of("legacy",UtteranceEvidence.PROTOCOL))
            assertThrows(IllegalArgumentException.class,()->new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),protocol,"1.7"));
        assertThrows(IllegalArgumentException.class,()->new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","","unused","unused",MODEL,Duration.ofSeconds(1),OUTPUT_LIMIT,HttpClient.newHttpClient(),UtteranceEvidence.GROUNDED_PROTOCOL,"../../other"));
    }

    @Test void copiedExampleIdsCannotPassSourceValidation() {
        var input=input("의사: 검사 결과 미확인.","audio/wav");
        assertEquals("AI_EVIDENCE_INVALID",assertThrows(AiProviderException.class,()->new UtteranceEvidence(json,input).resolve(output("검사 결과 미확인.",List.of("example-a-090")).toString(),true)).code());
    }

    @Test void consumedHoldoutCannotBeReusedAsIndependentForNewCandidate() throws Exception {
        var reviewed=SummaryEvidenceEvaluationMain.campaignCases("reviewed-development");
        assertEquals(12,reviewed.size());
        assertEquals(12,reviewed.stream().map(EvalCase::id).distinct().count());
        assertThrows(IllegalArgumentException.class,()->runCampaign(new String[]{"new-holdout","1.7","false"},SummaryEvidenceEvaluationMain.RESULTS,1.0,true));
        assertThrows(IllegalArgumentException.class,()->runCampaign(new String[]{"development","1.7","false"},RESULTS,3.0,false));
    }
}
