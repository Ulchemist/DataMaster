package com.datamaster.app.api;

import com.datamaster.app.domain.AnalysisProfile;
import com.datamaster.app.domain.ColumnProfile;
import com.datamaster.app.domain.FieldMapping;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.QualityReport;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.SpreadsheetImportService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisControllerTest {
    @Test
    void unfamiliarHeaderWithOnlyUnknownLocalMappingLeavesAiCandidateSpaceOpen() {
        ColumnProfile column = new ColumnProfile("col_novel", "含税销售贡献口径", "private.xlsx", 8,
                "DECIMAL", 100, 99, 100, new BigDecimal("0.99"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("40"), BigDecimal.ZERO, 50, false);
        FieldMapping unknown = new FieldMapping("col_novel", "unknown", "UNKNOWN", column.header(),
                "private.xlsx", 8, new BigDecimal("0.2"), false, 100, 99, 100,
                new BigDecimal("0.99"), new BigDecimal("0.99"), List.of());
        AnalysisProfile profile = new AnalysisProfile(List.of(unknown), List.of(), List.of(), List.of(),
                100, List.of(column));

        var payload = AiAnalysisController.schemaPayload("deepseek", profile);
        var request = payload.request();

        assertThat(request.columns()).singleElement().satisfies(value -> {
            assertThat(value.columnId()).isEqualTo("col_1");
            assertThat(value.candidateSemantics()).isEmpty();
        });
        assertThat(payload.localColumnIds()).containsEntry("col_1", "col_novel");
        assertThat(request.toString()).doesNotContain("private.xlsx");
    }

    @Test
    void mostlyTextColumnDoesNotSendAccidentalHighPrecisionIdentifierAsNumericProfile() {
        ColumnProfile column = new ColumnProfile("remarks", "备注", "private.xlsx", 2,
                "TEXT", 100, 1, 100, BigDecimal.ONE,
                new BigDecimal("1.2"),
                new BigDecimal("28170655122815592937281559293628155929342815592932"),
                new BigDecimal("72604781244370084889902821932075768774398434789.2762"),
                BigDecimal.ZERO, 100, false);
        AnalysisProfile profile = new AnalysisProfile(List.of(), List.of(), List.of(), List.of(),
                100, List.of(column));

        var payload = AiAnalysisController.schemaPayload("deepseek", profile);

        assertThat(payload.request().columns()).singleElement()
                .extracting(value -> value.numericProfile())
                .isNull();
    }

    @Test
    void deepInsightsUseTheSelectedMonthWithoutPersistingPeriodSpecificClaims() {
        AnalysisFixture fixture = analysisWithTwoMonths();
        AnalysisService analysis = fixture.analysis();
        var live = analysis.requireSession(fixture.id());
        AiAnalysisController.ProviderRequest request = new AiAnalysisController.ProviderRequest(
                "deepseek", "cumulative", List.of("2026"), List.of("2026-06"));

        var scoped = AiAnalysisController.deepInsightSession(analysis, live, request);

        assertThat(scoped.result().rowCount()).isEqualTo(1);
        assertThat(scoped.result().summary().revenue()).isEqualByComparingTo("600.00");
        assertThat(scoped.result().monthly()).singleElement()
                .satisfies(value -> assertThat(value.month()).isEqualTo("2026-06"));
        assertThat(AiAnalysisController.shouldPersistDeepInsights(request)).isFalse();
        assertThat(analysis.requireSession(live.result().id())).isSameAs(live);
        assertThat(analysis.requireSession(live.result().id()).result().rowCount()).isEqualTo(2);
    }

    @Test
    void unscopedDeepInsightsKeepTheExistingWorkspacePersistenceBehaviour() {
        AnalysisFixture fixture = analysisWithTwoMonths();
        AnalysisService analysis = fixture.analysis();
        var live = analysis.requireSession(fixture.id());
        AiAnalysisController.ProviderRequest request = new AiAnalysisController.ProviderRequest("deepseek");

        var unscoped = AiAnalysisController.deepInsightSession(analysis, live, request);

        assertThat(unscoped).isSameAs(live);
        assertThat(AiAnalysisController.shouldPersistDeepInsights(request)).isTrue();
    }

    private static AnalysisFixture analysisWithTwoMonths() {
        AnalysisService analysis = new AnalysisService();
        List<DataRow> rows = List.of(
                new DataRow("may.xlsx", LocalDate.of(2026, 5, 1), "产品甲", "客户甲",
                        BigDecimal.ONE, new BigDecimal("400"), new BigDecimal("240"), new BigDecimal("20")),
                new DataRow("june.xlsx", LocalDate.of(2026, 6, 1), "产品乙", "客户乙",
                        BigDecimal.ONE, new BigDecimal("600"), new BigDecimal("300"), new BigDecimal("30"))
        );
        var result = analysis.analyze(new SpreadsheetImportService.ImportResult(rows, 2,
                new QualityReport(2, 2, 0, 0, 0, 0, List.of())));
        return new AnalysisFixture(analysis, result.id());
    }

    private record AnalysisFixture(AnalysisService analysis, String id) {
    }
}
