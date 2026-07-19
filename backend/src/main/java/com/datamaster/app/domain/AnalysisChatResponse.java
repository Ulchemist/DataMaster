package com.datamaster.app.domain;

import java.time.Instant;
import java.util.List;

public record AnalysisChatResponse(
        String answer,
        String message,
        String reply,
        String content,
        List<SuggestedAction> suggestedActions,
        List<SuggestedAction> suggestions,
        List<Evidence> evidence,
        String provider,
        String model,
        Instant generatedAt
) {
    public AnalysisChatResponse {
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
        suggestions = suggestions == null ? suggestedActions : List.copyOf(suggestions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record SuggestedAction(
            String type,
            String label,
            String dimension,
            List<String> values,
            String value
    ) {
        public SuggestedAction {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record Evidence(String id, String display) {
    }
}
