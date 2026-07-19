package com.datamaster.app.domain;

import java.util.List;

/** Semantic profile of the imported workbook, separate from the calculated result. */
public record AnalysisProfile(
        List<FieldMapping> mappings,
        List<MappingIssue> mappingIssues,
        List<String> availableDimensions,
        List<String> availableMetrics,
        long profiledRows,
        List<ColumnProfile> columns
) {
    public AnalysisProfile {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        mappingIssues = mappingIssues == null ? List.of() : List.copyOf(mappingIssues);
        availableDimensions = availableDimensions == null ? List.of() : List.copyOf(availableDimensions);
        availableMetrics = availableMetrics == null ? List.of() : List.copyOf(availableMetrics);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public AnalysisProfile(List<FieldMapping> mappings, List<MappingIssue> mappingIssues,
                           List<String> availableDimensions, List<String> availableMetrics,
                           long profiledRows) {
        this(mappings, mappingIssues, availableDimensions, availableMetrics, profiledRows, List.of());
    }

    public static AnalysisProfile empty() {
        return new AnalysisProfile(List.of(), List.of(), List.of(), List.of(), 0, List.of());
    }

    public boolean hasSelected(String fieldId) {
        return mappings.stream().anyMatch(value -> value.selected() && fieldId.equals(value.fieldId()));
    }

    public double coverageOf(String fieldId) {
        if (profiledRows <= 0) return 0;
        long nonBlank = mappings.stream()
                .filter(value -> value.selected() && fieldId.equals(value.fieldId()))
                .mapToLong(FieldMapping::validCount)
                .sum();
        return Math.min(1d, (double) nonBlank / profiledRows);
    }
}
