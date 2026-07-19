package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record DataRow(
        String sourceFile,
        LocalDate date,
        String product,
        String customer,
        BigDecimal quantity,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal expense,
        Map<String, String> dimensions,
        Set<String> validMetrics,
        Map<String, BigDecimal> measures
) {
    public DataRow {
        sourceFile = text(sourceFile, "未知文件");
        product = nullableText(product);
        customer = nullableText(customer);
        quantity = number(quantity);
        revenue = number(revenue);
        cost = number(cost);
        expense = number(expense);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        validMetrics = validMetrics == null ? Set.of() : Set.copyOf(validMetrics);
        measures = measures == null ? Map.of() : Map.copyOf(measures);
    }

    public DataRow(String sourceFile, LocalDate date, String product, String customer,
                   BigDecimal quantity, BigDecimal revenue, BigDecimal cost, BigDecimal expense) {
        this(sourceFile, date, product, customer, quantity, revenue, cost, expense, Map.of(),
                Set.of("quantity", "revenue", "cost", "expense"), Map.of());
    }

    public DataRow(String sourceFile, LocalDate date, String product, String customer,
                   BigDecimal quantity, BigDecimal revenue, BigDecimal cost, BigDecimal expense,
                   Map<String, String> dimensions) {
        this(sourceFile, date, product, customer, quantity, revenue, cost, expense, dimensions,
                Set.of("quantity", "revenue", "cost", "expense"), Map.of());
    }

    /** Backward-compatible constructor used by existing imports and tests. */
    public DataRow(String sourceFile, LocalDate date, String product, String customer,
                   BigDecimal quantity, BigDecimal revenue, BigDecimal cost, BigDecimal expense,
                   Map<String, String> dimensions, Set<String> validMetrics) {
        this(sourceFile, date, product, customer, quantity, revenue, cost, expense, dimensions,
                validMetrics, Map.of());
    }

    public boolean metricValid(String metric) {
        return validMetrics.contains(metric);
    }

    /** Returns a named row-level measure without silently substituting another business definition. */
    public BigDecimal measure(String metric) {
        if (metric == null) return BigDecimal.ZERO;
        return switch (metric) {
            case "quantity" -> quantity;
            case "revenue" -> revenue;
            case "cost" -> cost;
            case "expense" -> expense;
            default -> measures.getOrDefault(metric, BigDecimal.ZERO);
        };
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BigDecimal number(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
