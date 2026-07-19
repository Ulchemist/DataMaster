package com.datamaster.app.domain;

import java.util.List;
import java.util.Map;

public record WorkspaceCurrentResponse(
        AnalysisResult analysis,
        List<WorkspaceSource> sources,
        Map<String, String> mapping
) {
    public WorkspaceCurrentResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
    }

    public static WorkspaceCurrentResponse empty() {
        return new WorkspaceCurrentResponse(null, List.of(), Map.of());
    }

    /**
     * Source-level lineage retained independently from the calculated analysis.  Period metadata
     * is deterministic and prompt-ready: the embedded AI may explain or propose a correction when
     * {@code needsAiReview} is true, but an AI guess never silently rewrites source rows.
     */
    public record WorkspaceSource(
            String id,
            String name,
            long size,
            String contentType,
            String sha256,
            List<String> periods,
            String periodBasis,
            String seriesKey,
            boolean needsAiReview,
            String relationshipNote
    ) {
        public WorkspaceSource {
            periods = periods == null ? List.of() : List.copyOf(periods);
        }
    }
}
