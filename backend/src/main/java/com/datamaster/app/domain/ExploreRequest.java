package com.datamaster.app.domain;

import java.util.List;
import java.util.Map;

/** A bounded slice request over the imported row set retained by one analysis session. */
public record ExploreRequest(
        String groupBy,
        Map<String, List<String>> filters,
        String quantityMetric,
        String costMetric,
        String sortBy,
        String search,
        String profitFilter,
        Integer offset,
        Integer limit,
        String periodMode,
        List<String> years,
        List<String> months,
        Boolean includeFilterOptions
) {
    public ExploreRequest {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        years = years == null ? List.of() : List.copyOf(years);
        months = months == null ? List.of() : List.copyOf(months);
    }

    /** Backwards-compatible constructor for callers that keep the default revenue ordering. */
    public ExploreRequest(String groupBy, Map<String, List<String>> filters,
                          String quantityMetric, String costMetric, Integer limit) {
        this(groupBy, filters, quantityMetric, costMetric, null, null, null, null, limit,
                null, List.of(), List.of(), null);
    }

    /** Backwards-compatible constructor for callers that select a sorting metric. */
    public ExploreRequest(String groupBy, Map<String, List<String>> filters,
                          String quantityMetric, String costMetric, String sortBy, Integer limit) {
        this(groupBy, filters, quantityMetric, costMetric, sortBy, null, null, null, limit,
                null, List.of(), List.of(), null);
    }

    /** Backwards-compatible constructor for callers using search and offset pagination. */
    public ExploreRequest(String groupBy, Map<String, List<String>> filters,
                          String quantityMetric, String costMetric, String sortBy, String search,
                          Integer offset, Integer limit) {
        this(groupBy, filters, quantityMetric, costMetric, sortBy, search, null, offset, limit,
                null, List.of(), List.of(), null);
    }

    /** Backwards-compatible constructor matching the pre-period-scope canonical request. */
    public ExploreRequest(String groupBy, Map<String, List<String>> filters,
                          String quantityMetric, String costMetric, String sortBy, String search,
                          String profitFilter, Integer offset, Integer limit) {
        this(groupBy, filters, quantityMetric, costMetric, sortBy, search, profitFilter, offset, limit,
                null, List.of(), List.of(), null);
    }

    /** Backwards-compatible constructor matching the period-aware request before response shaping. */
    public ExploreRequest(String groupBy, Map<String, List<String>> filters,
                          String quantityMetric, String costMetric, String sortBy, String search,
                          String profitFilter, Integer offset, Integer limit, String periodMode,
                          List<String> years, List<String> months) {
        this(groupBy, filters, quantityMetric, costMetric, sortBy, search, profitFilter, offset, limit,
                periodMode, years, months, null);
    }

    public boolean wantsFilterOptions() {
        return !Boolean.FALSE.equals(includeFilterOptions);
    }
}
