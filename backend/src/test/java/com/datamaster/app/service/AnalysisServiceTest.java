package com.datamaster.app.service;

import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.QualityReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisServiceTest {
    @Test
    void calculatesProfitAndMarginsDeterministically() {
        List<DataRow> rows = List.of(
                new DataRow("a.xlsx", LocalDate.of(2026, 1, 1), "A", "甲",
                        bd("2"), bd("1000"), bd("600"), bd("250")),
                new DataRow("b.xlsx", LocalDate.of(2026, 1, 2), "B", "乙",
                        bd("1"), bd("500"), bd("450"), bd("300"))
        );

        FinancialSummary result = AnalysisService.summary(rows);

        assertThat(result.revenue()).isEqualByComparingTo("1500.00");
        assertThat(result.cost()).isEqualByComparingTo("1050.00");
        assertThat(result.grossComparableRevenue()).isEqualByComparingTo("1500.00");
        assertThat(result.grossComparableCost()).isEqualByComparingTo("1050.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("450.00");
        assertThat(result.expenses()).isEqualByComparingTo("550.00");
        assertThat(result.operatingComparableRevenue()).isEqualByComparingTo("1500.00");
        assertThat(result.operatingComparableCost()).isEqualByComparingTo("1050.00");
        assertThat(result.operatingComparableExpenses()).isEqualByComparingTo("550.00");
        assertThat(result.operatingProfit()).isEqualByComparingTo("-100.00");
        assertThat(result.grossMargin()).isEqualByComparingTo("0.3000");
        assertThat(result.operatingMargin()).isEqualByComparingTo("-0.0667");
    }

    @Test
    void zeroRevenueDoesNotDivideByZero() {
        FinancialSummary result = AnalysisService.summary(List.of(
                new DataRow("a.csv", null, null, null, BigDecimal.ZERO,
                        BigDecimal.ZERO, bd("100"), bd("20"))
        ));

        assertThat(result.operatingProfit()).isEqualByComparingTo("-120.00");
        assertThat(result.grossMargin()).isEqualByComparingTo("0.0000");
        assertThat(result.operatingMargin()).isEqualByComparingTo("0.0000");
    }

    @Test
    void releasesOldLargeAnalysisSessionsInsteadOfRetainingThemForever() {
        AnalysisService service = new AnalysisService();
        String firstId = null;
        String latestId = null;
        for (int index = 0; index < 5; index++) {
            List<DataRow> rows = List.of(new DataRow("data.csv", null, "A", "甲", BigDecimal.ONE,
                    bd("100"), bd("50"), bd("10")));
            QualityReport quality = new QualityReport(1, 1, 0, 0, 0, 0, List.of());
            latestId = service.analyze(new SpreadsheetImportService.ImportResult(rows, 1, quality)).id();
            if (firstId == null) firstId = latestId;
        }

        String expiredId = firstId;
        String activeId = latestId;
        assertThatThrownBy(() -> service.requireSession(expiredId))
                .isInstanceOf(AnalysisService.AnalysisNotFoundException.class);
        assertThat(service.requireSession(activeId).result().id()).isEqualTo(activeId);

        service.releaseSession(activeId);
        assertThatThrownBy(() -> service.requireSession(activeId))
                .isInstanceOf(AnalysisService.AnalysisNotFoundException.class);
    }

    @Test
    void buildsAnExportOnlySessionForSelectedMonthsWithoutChangingTheLiveSession() {
        AnalysisService service = new AnalysisService();
        List<DataRow> rows = List.of(
                new DataRow("may.xlsx", LocalDate.of(2026, 5, 1), "A", "甲",
                        bd("2"), bd("400"), bd("240"), bd("20")),
                new DataRow("june.xlsx", LocalDate.of(2026, 6, 1), "B", "乙",
                        bd("3"), bd("600"), bd("300"), bd("30"))
        );
        var result = service.analyze(new SpreadsheetImportService.ImportResult(rows, 2,
                new QualityReport(2, 2, 0, 0, 0, 0,
                        List.of("may.xlsx 包含旧期间公式风险", "june.xlsx 包含当前期间公式风险",
                                "全项目发现重复明细"))));
        var live = service.requireSession(result.id());

        var scoped = service.scopedSession(live, "cumulative", List.of("2026"), List.of("2026-06"));

        assertThat(scoped.rows()).singleElement().satisfies(row -> assertThat(row.product()).isEqualTo("B"));
        assertThat(scoped.result().rowCount()).isEqualTo(1);
        assertThat(scoped.result().sourceFileCount()).isEqualTo(1);
        assertThat(scoped.result().quality().warnings())
                .contains("june.xlsx 包含当前期间公式风险")
                .anyMatch(value -> value.contains("仅使用 1 / 2 个项目数据源"))
                .noneMatch(value -> value.contains("may.xlsx") || value.contains("全项目发现重复明细"));
        assertThat(scoped.result().summary().revenue()).isEqualByComparingTo("600.00");
        assertThat(scoped.result().summary().operatingProfit()).isEqualByComparingTo("270.00");
        assertThat(live.result().rowCount()).isEqualTo(2);
        assertThat(service.requireSession(result.id()).result().summary().revenue())
                .isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> service.scopedSession(live, "month", List.of(), List.of("2026/06")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
