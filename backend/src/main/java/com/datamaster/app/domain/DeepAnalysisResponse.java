package com.datamaster.app.domain;

import java.time.Instant;
import java.util.List;

/** Evidence-grounded AI interpretation.  The deterministic AnalysisResult remains the source of truth. */
public record DeepAnalysisResponse(
        List<DeepInsight> insights,
        String providerId,
        String model,
        Instant generatedAt,
        boolean localAnalysisPreserved
) {
    public DeepAnalysisResponse {
        insights = insights == null ? List.of() : List.copyOf(insights);
    }

    public record DeepInsight(
            String claimType,
            String category,
            String title,
            String finding,
            String action,
            List<EvidenceReference> evidence
    ) {
        public DeepInsight {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record EvidenceReference(String evidenceId, String display) {
    }
}
