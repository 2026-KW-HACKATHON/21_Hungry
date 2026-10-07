package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiContractResourceTests {
    private final ObjectMapper json=new ObjectMapper();

    @Test void analysisSchemaPreventsModelChosenDatabaseIdsAndUsesVersionedConditionalField()throws Exception{
        JsonNode schema=json.readTree(new ClassPathResource("ai/analysis-output.schema.json").getContentAsString(StandardCharsets.UTF_8));
        JsonNode item=schema.at("/$defs/medicationPayload/properties/supersedesMedicationId");
        assertEquals("null",item.get("type").asText());
        JsonNode required=schema.at("/properties/items/items/required");
        assertTrue(required.toString().contains("isConditional"));assertFalse(required.toString().contains("\"conditional\""));
    }

    @Test void koreanNumericFixtureIsVersionedAndKeepsUnknownTimesExplicit()throws Exception{
        JsonNode fixture=json.readTree(new ClassPathResource("ai/fixtures/analysis-korean-medication-v1.0.json").getContentAsString(StandardCharsets.UTF_8));
        assertEquals("1.0",fixture.get("fixtureVersion").asText());assertTrue(fixture.get("source").asText().contains("0.5정"));assertTrue(fixture.get("source").asText().contains("1.5mg"));assertTrue(fixture.at("/expectations/unknownMedicationTimes").asBoolean());assertFalse(fixture.at("/expectations/inventedTimesAllowed").asBoolean());
    }
}
