package com.datamaster.app.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tells every consumer which conclusions are mathematically supported by the imported fields.
 * A zero value must never be interpreted as an available metric without consulting this record.
 */
public record AnalysisCapabilities(
        boolean hasRevenue,
        boolean hasCost,
        boolean hasExpenses,
        boolean hasQuantity,
        boolean hasDate,
        boolean hasProduct,
        boolean hasCustomer,
        boolean grossProfitAvailable,
        boolean operatingProfitAvailable,
        BigDecimal revenueCoverage,
        BigDecimal costCoverage,
        BigDecimal expenseCoverage,
        BigDecimal grossProfitCoverage,
        BigDecimal operatingProfitCoverage,
        BigDecimal grossProfitRevenueCoverage,
        BigDecimal operatingProfitRevenueCoverage,
        long grossProfitExcludedRows,
        long operatingProfitExcludedRows,
        BigDecimal grossProfitExcludedRevenue,
        BigDecimal operatingProfitExcludedRevenue,
        List<String> unavailableReasons
) {
    public AnalysisCapabilities {
        revenueCoverage = revenueCoverage == null ? BigDecimal.ZERO : revenueCoverage;
        costCoverage = costCoverage == null ? BigDecimal.ZERO : costCoverage;
        expenseCoverage = expenseCoverage == null ? BigDecimal.ZERO : expenseCoverage;
        grossProfitCoverage = grossProfitCoverage == null ? BigDecimal.ZERO : grossProfitCoverage;
        operatingProfitCoverage = operatingProfitCoverage == null ? BigDecimal.ZERO : operatingProfitCoverage;
        grossProfitRevenueCoverage = grossProfitRevenueCoverage == null ? BigDecimal.ZERO : grossProfitRevenueCoverage;
        operatingProfitRevenueCoverage = operatingProfitRevenueCoverage == null ? BigDecimal.ZERO : operatingProfitRevenueCoverage;
        grossProfitExcludedRevenue = grossProfitExcludedRevenue == null ? BigDecimal.ZERO : grossProfitExcludedRevenue;
        operatingProfitExcludedRevenue = operatingProfitExcludedRevenue == null ? BigDecimal.ZERO : operatingProfitExcludedRevenue;
        unavailableReasons = unavailableReasons == null ? List.of() : List.copyOf(unavailableReasons);
    }

    public AnalysisCapabilities(boolean hasRevenue, boolean hasCost, boolean hasExpenses,
                                boolean hasQuantity, boolean hasDate, boolean hasProduct,
                                boolean hasCustomer, boolean grossProfitAvailable,
                                boolean operatingProfitAvailable, BigDecimal revenueCoverage,
                                BigDecimal costCoverage, BigDecimal expenseCoverage,
                                List<String> unavailableReasons) {
        this(hasRevenue, hasCost, hasExpenses, hasQuantity, hasDate, hasProduct, hasCustomer,
                grossProfitAvailable, operatingProfitAvailable, revenueCoverage, costCoverage,
                expenseCoverage, grossProfitAvailable ? BigDecimal.ONE : BigDecimal.ZERO,
                operatingProfitAvailable ? BigDecimal.ONE : BigDecimal.ZERO,
                grossProfitAvailable ? BigDecimal.ONE : BigDecimal.ZERO,
                operatingProfitAvailable ? BigDecimal.ONE : BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, unavailableReasons);
    }

    public static AnalysisCapabilities legacyComplete() {
        return new AnalysisCapabilities(true, true, true, true, true, true, true,
                true, true, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
