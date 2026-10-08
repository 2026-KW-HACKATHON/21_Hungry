package com.kw.knowone.encounter.processing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SummaryEvaluationTests {
    @Test void alignedFullTranslationsAndFixedSplit() throws Exception {
        SummaryEvaluationMain.validateDataset();
        assertEquals(6,SummaryEvaluationMain.cases("development").size());
        assertEquals(4,SummaryEvaluationMain.cases("holdout").size());
        assertEquals(8,SummaryEvaluationMain.cases("synthetic").size());
    }
    @Test void budgetUsesFullOutputAndNeverAssumesCache() {
        double bound=SummaryEvaluationMain.reserve("한글");
        assertTrue(bound>=2*12000*4.5/1_000_000);
        assertTrue(SummaryEvaluationMain.canAfford(3-bound,bound));
        assertFalse(SummaryEvaluationMain.canAfford(3-bound+.000001,bound));
        assertFalse(SummaryEvaluationMain.canAfford(-1,bound));
    }
    @Test void costIncludesReasoningOnceAndAccountsForCache() {
        var usage=new ObjectMapper().readTree("""
            {"input_tokens":10000,"input_tokens_details":{"cached_tokens":2000},
            "output_tokens":5000,"output_tokens_details":{"reasoning_tokens":3000}}
            """);
        assertEquals(.02865,SummaryEvaluationMain.cost(usage),.00000001);
    }
    @Test void canonicalFingerprintIgnoresObjectOrderNotPromptOrInput() throws Exception {
        var json=new ObjectMapper();
        String a=SummaryEvaluationMain.canonical(json.readTree("{\"b\":2,\"a\":1}"));
        String b=SummaryEvaluationMain.canonical(json.readTree("{\"a\":1,\"b\":2}"));
        assertEquals(SummaryEvaluationMain.hash(a),SummaryEvaluationMain.hash(b));
        assertNotEquals(SummaryEvaluationMain.hash(a),SummaryEvaluationMain.hash(a+"changed prompt"));
    }
    @Test void evaluationUsesProductionRequestAndRejectsCapWithoutDatabase() throws Exception {
        var json=new ObjectMapper();
        var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1","",
            "unused","unused",SummaryEvaluationMain.MODEL,Duration.ofSeconds(1),12000,HttpClient.newHttpClient());
        var input=SummaryEvaluationMain.cases("development").getFirst().input();
        var body=json.valueToTree(adapter.analysisRequest(input,"candidate marker",true));
        assertEquals("candidate marker",body.at("/input/0/content/0/text").asText());
        assertEquals("developer",body.at("/input/0/role").asText());
        assertEquals("user",body.at("/input/1/role").asText());
        assertEquals(12000,body.path("max_output_tokens").asInt());
        assertFalse(body.path("store").asBoolean());
        assertTrue(body.at("/text/format/strict").asBoolean());
        var properties=body.at("/text/format/schema/properties").properties().stream().map(java.util.Map.Entry::getKey).toList();
        assertTrue(properties.indexOf("evidence")<properties.indexOf("details"));
        var production=json.valueToTree(adapter.analysisRequest(input,"candidate marker"));
        var productionProperties=production.at("/text/format/schema/properties").properties().stream().map(java.util.Map.Entry::getKey).toList();
        assertTrue(productionProperties.indexOf("details")<productionProperties.indexOf("evidence"));
        assertEquals(production,body); // JSON contract identical; order alone differs in the experiment.
        assertFalse(body.has("reasoning"));
        assertFalse(body.has("temperature"));
        assertTrue(body.at("/input/1/content/0/text").asText().contains("[054]"));
        assertEquals("AI_OUTPUT_INCOMPLETE",assertThrows(AiProviderException.class,()->adapter.parseAnalysis(
            json.readTree("{\"status\":\"completed\",\"usage\":{\"output_tokens\":12000}}"),input,"candidate")).code());
        assertTrue(SummaryEvaluationMain.reserve(body.toString())>body.toString().getBytes(StandardCharsets.UTF_8).length*.75/1_000_000);
    }
    @Test void evidenceFirstChangesOnlyOrderAndCacheIncludesSchemaOrder() throws Exception {
        var json=new ObjectMapper();
        var schema=json.readTree(SummaryEvaluationMain.read(java.nio.file.Path.of("src/main/resources/ai/analysis-output.schema.json")));
        var reordered=OpenAiProcessingAdapter.evidenceFirstSchema(schema);
        assertEquals(schema,reordered);
        assertNotEquals(SummaryEvaluationMain.hash(SummaryEvaluationMain.canonical(schema)),
            SummaryEvaluationMain.hash(SummaryEvaluationMain.canonical(reordered)));
    }
}
