package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExploreResponse(
        String analysisId,
        String groupBy,
        Map<String, String> dimensionLabels,
        List<String> availableDimensions,
        List<String> availableQuantityMetrics,
        List<String> availableCostMetrics,
        String quantityMetric,
        String costMetric,
        String periodMode,
        List<String> appliedYears,
        List<String> appliedMonths,
        List<String> availableYears,
        List<String> availableMonths,
        Map<String, List<String>> appliedFilters,
        Map<String, List<String>> filterOptions,
        PeriodSummary periodSummary,
        ExploreItem totals,
        List<ExploreItem> items,
        String search,
        String profitFilter,
        int offset,
        int pageSize,
        int returnedGroups,
        int totalGroups,
        boolean truncated,
        boolean hasMore,
        Map<String, String> drillSuggestions
) {
    public ExploreResponse {
        drillSuggestions = immutableMap(drillSuggestions);
        dimensionLabels = immutableMap(dimensionLabels);
        availableDimensions = availableDimensions == null ? List.of() : List.copyOf(availableDimensions);
        availableQuantityMetrics = availableQuantityMetrics == null
                ? List.of() : List.copyOf(availableQuantityMetrics);
        availableCostMetrics = availableCostMetrics == null ? List.of() : List.copyOf(availableCostMetrics);
        appliedYears = appliedYears == null ? List.of() : List.copyOf(appliedYears);
        appliedMonths = appliedMonths == null ? List.of() : List.copyOf(appliedMonths);
        availableYears = availableYears == null ? List.of() : List.copyOf(availableYears);
        availableMonths = availableMonths == null ? List.of() : List.copyOf(availableMonths);
        appliedFilters = immutableListMap(appliedFilters);
        filterOptions = immutableListMap(filterOptions);
        items = items == null ? List.of() : List.copyOf(items);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static Map<String, List<String>> immutableListMap(Map<String, List<String>> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    public record ExploreItem(
            String key,
            String name,
            long records,
            long productCount,
            long customerCount,
            List<String> salesGroups,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal expense,
            BigDecimal comparableRevenue,
            BigDecimal comparableCost,
            BigDecimal profit,
            BigDecimal margin,
            BigDecimal profitCoverage,
            BigDecimal operatingComparableRevenue,
            BigDecimal operatingComparableCost,
            BigDecimal operatingComparableExpense,
            BigDecimal operatingProfit,
            BigDecimal operatingMargin,
            BigDecimal operatingProfitCoverage,
            boolean operatingProfitAvailable,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal share,
            long comparableRows,
            long excludedProfitRows,
            boolean profitAvailable,
            boolean quantityAvailable,
            BigDecimal salesQuantity,
            BigDecimal outboundQuantity,
            BigDecimal convertedQuantity,
            BigDecimal averageCost,
            BigDecimal unitGrossProfit,
            BigDecimal transferCost,
            BigDecimal transferComparableRevenue,
            BigDecimal transferComparableCost,
            BigDecimal transferGrossProfit,
            BigDecimal transferGrossMargin,
            BigDecimal transferProfitCoverage,
            boolean outboundQuantityAvailable,
            boolean convertedQuantityAvailable,
            boolean transferProfitAvailable,
            BigDecimal confirmedCost,
            BigDecimal confirmedComparableRevenue,
            BigDecimal confirmedComparableCost,
            BigDecimal confirmedGrossProfit,
            BigDecimal confirmedGrossMargin,
            BigDecimal confirmedProfitCoverage,
            boolean confirmedProfitAvailable,
            String spec,
            List<String> specVariants
    ) {
        public ExploreItem {
            salesGroups = salesGroups == null ? List.of() : List.copyOf(salesGroups);
            specVariants = specVariants == null ? List.of() : List.copyOf(specVariants);
        }
    }

    /** Period-filtered headline metrics for overview and profit pages. */
    public record PeriodSummary(
            long records,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal expense,
            BigDecimal grossComparableRevenue,
            BigDecimal grossComparableCost,
            BigDecimal grossProfit,
            BigDecimal grossMargin,
            BigDecimal grossProfitCoverage,
            boolean grossProfitAvailable,
            BigDecimal operatingComparableRevenue,
            BigDecimal operatingComparableCost,
            BigDecimal operatingComparableExpense,
            BigDecimal operatingProfit,
            BigDecimal operatingMargin,
            BigDecimal operatingProfitCoverage,
            boolean operatingProfitAvailable,
            BigDecimal quantity,
            BigDecimal salesQuantity,
            BigDecimal convertedQuantity,
            String quantityMetric,
            boolean revenueAvailable,
            boolean costAvailable,
            boolean expenseAvailable,
            boolean quantityAvailable,
            long grossComparableRows,
            long operatingComparableRows,
            long grossProfitExcludedRows,
            long operatingProfitExcludedRows,
            BigDecimal grossProfitExcludedRevenue,
            BigDecimal operatingProfitExcludedRevenue
    ) {
    }
}
