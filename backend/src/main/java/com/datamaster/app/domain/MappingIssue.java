package com.datamaster.app.domain;

import java.util.List;

/** A visible semantic-mapping warning.  Severity is INFO, WARNING or BLOCKING. */
public record MappingIssue(
        String severity,
        String code,
        String message,
        String source,
        List<String> candidates
) {
    public MappingIssue {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
