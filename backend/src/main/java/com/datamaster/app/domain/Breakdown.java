package com.datamaster.app.domain;

import java.math.BigDecimal;

public record Breakdown(
        String name,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal grossProfit,
        BigDecimal expenses,
        BigDecimal operatingProfit,
        BigDecimal grossMargin,
        BigDecimal operatingMargin,
        BigDecimal quantity,
        boolean grossProfitAvailable,
        boolean operatingProfitAvailable,
        BigDecimal grossProfitCoverage,
        BigDecimal operatingProfitCoverage,
        long grossProfitExcludedRows,
        long operatingProfitExcludedRows
) {
    public Breakdown(String name, BigDecimal revenue, BigDecimal cost, BigDecimal grossProfit,
                     BigDecimal expenses, BigDecimal operatingProfit, BigDecimal grossMargin,
                     BigDecimal operatingMargin, BigDecimal quantity) {
        this(name, revenue, cost, grossProfit, expenses, operatingProfit, grossMargin, operatingMargin,
                quantity, true, true, BigDecimal.ONE, BigDecimal.ONE, 0, 0);
    }
}
