package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * A scored interpretation of one source column.  All plausible candidates are returned so
 * the UI (and the AI mapping assistant) can explain or override a decision instead of hiding it.
 */
public record FieldMapping(
        String columnId,
        String fieldId,
        String role,
        String header,
        String source,
        int columnIndex,
        BigDecimal confidence,
        boolean selected,
        long nonBlankCount,
        long validCount,
        long rowCount,
        BigDecimal coverage,
        BigDecimal validCoverage,
        List<String> alternatives
) {
    public FieldMapping {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        confidence = confidence == null ? BigDecimal.ZERO : confidence;
        coverage = coverage == null ? BigDecimal.ZERO : coverage;
        validCoverage = validCoverage == null ? BigDecimal.ZERO : validCoverage;
    }
}
