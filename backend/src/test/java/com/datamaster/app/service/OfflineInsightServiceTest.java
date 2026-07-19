package com.datamaster.app.service;

import com.datamaster.app.domain.FinancialSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineInsightServiceTest {
    @Test
    void expenseRateUsesOperatingComparableBasisInsteadOfUnrelatedFullTotals() {
        FinancialSummary summary = new FinancialSummary(
                bd("1000"), bd("700"), bd("100"), bd("60"), bd("40"), bd("200"),
                bd("100"), bd("60"), bd("10"), bd("30"), bd("0.40"), bd("0.30"));

        assertThat(OfflineInsightService.generate(summary, List.of(), List.of()))
                .extracting(value -> value.title())
                .doesNotContain("压降期间费用率");
    }

    @Test
    void highComparableExpenseRateProducesComparableEvidence() {
        FinancialSummary summary = new FinancialSummary(
                bd("1000"), bd("700"), bd("100"), bd("60"), bd("40"), bd("10"),
                bd("100"), bd("60"), bd("20"), bd("20"), bd("0.40"), bd("0.20"));

        assertThat(OfflineInsightService.generate(summary, List.of(), List.of()))
                .filteredOn(value -> value.title().equals("压降期间费用率"))
                .singleElement()
                .satisfies(value -> assertThat(value.evidence()).contains("经营可比费用", "20.0%"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
