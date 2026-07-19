package com.datamaster.app.domain;

import java.util.List;

/** A generic breakdown for report-specific dimensions such as region, channel or sales team. */
public record DynamicBreakdown(
        String dimensionId,
        String dimensionLabel,
        List<Breakdown> values,
        int totalGroups,
        boolean truncated
) {
    public DynamicBreakdown {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
