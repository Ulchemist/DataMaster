package com.datamaster.app.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** User-selected PDF scope. Unknown section ids are ignored by the exporter. */
public record PdfReportRequest(
        String title,
        List<String> sections,
        Integer topN,
        String periodMode,
        List<String> years,
        List<String> months
) {
    private static final List<String> DEFAULT_SECTIONS = List.of(
            "overview", "trend", "product", "customer", "profit", "organization", "insights", "quality");

    public PdfReportRequest {
        title = title == null || title.isBlank() ? "DataMaster 经营分析报告" : title.strip();
        sections = normalizeSections(sections);
        topN = topN == null ? 15 : Math.max(5, Math.min(100, topN));
        years = years == null ? List.of() : List.copyOf(years);
        months = months == null ? List.of() : List.copyOf(months);
    }

    /** Backwards-compatible constructor for clients without period selection. */
    public PdfReportRequest(String title, List<String> sections, Integer topN) {
        this(title, sections, topN, null, List.of(), List.of());
    }

    public static PdfReportRequest complete() {
        return new PdfReportRequest(null, DEFAULT_SECTIONS, 15, "cumulative", List.of(), List.of());
    }

    public boolean includes(String section) {
        return sections.contains(section);
    }

    private static List<String> normalizeSections(List<String> values) {
        if (values == null || values.isEmpty()) return DEFAULT_SECTIONS;
        Set<String> allowed = Set.copyOf(DEFAULT_SECTIONS);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(value -> value != null && allowed.contains(value.strip()))
                .map(String::strip).forEach(result::add);
        return result.isEmpty() ? DEFAULT_SECTIONS : List.copyOf(result);
    }
}
