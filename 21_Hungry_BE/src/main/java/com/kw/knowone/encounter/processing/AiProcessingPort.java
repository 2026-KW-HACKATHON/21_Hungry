package com.kw.knowone.encounter.processing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface AiProcessingPort {
    TextResult transcribe(byte[] bytes, String mediaType);
    TextResult ocr(byte[] bytes, String mediaType);
    AnalysisResult analyze(AnalysisInput input);

    default AnalysisResult analyze(List<SourceText> sources) {
        return analyze(new AnalysisInput(null, Instant.now(), "Asia/Seoul", sources, List.of()));
    }

    record TextResult(String text, String provider, String model, String promptVersion,
            long inputTokens, long outputTokens) {
        public TextResult(String text) { this(text, null, null, null, 0, 0); }
    }
    record SourceText(String sourceId, int textVersion, String sourceType, String mediaType, String text) {
        public SourceText(String sourceId, int textVersion, String sourceType, String text) {
            this(sourceId, textVersion, sourceType, null, text);
        }
    }
    record KnownMedication(String name, String doseText, String frequencyText,
            LocalDate startsOn, LocalDate endsOn) { }
    record AnalysisInput(LocalDate occurredOn, Instant analyzedAt, String timezone,
            List<SourceText> sources, List<KnownMedication> knownMedications) { }
    record AnalysisItem(String itemKey, String itemType, String payloadJson, String evidenceJson,
            String reviewState, List<String> reviewReasons) { }
    record AnalysisResult(String summary, String detailsJson, String evidenceJson,
            List<AnalysisItem> items, String provider, String model, String promptVersion,
            long inputTokens, long outputTokens) {
        public AnalysisResult(String summary, String detailsJson, String evidenceJson) {
            this(summary, detailsJson, evidenceJson, List.of(), null, null, null, 0, 0);
        }
    }
}
