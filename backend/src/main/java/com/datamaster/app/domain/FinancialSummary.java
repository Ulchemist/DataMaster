package com.datamaster.app.domain;

import java.math.BigDecimal;

public record FinancialSummary(
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal grossComparableRevenue,
        BigDecimal grossComparableCost,
        BigDecimal grossProfit,
        BigDecimal expenses,
        BigDecimal operatingComparableRevenue,
        BigDecimal operatingComparableCost,
        BigDecimal operatingComparableExpenses,
        BigDecimal operatingProfit,
        BigDecimal grossMargin,
        BigDecimal operatingMargin
) {
}
