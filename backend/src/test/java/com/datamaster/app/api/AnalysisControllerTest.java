package com.datamaster.app.api;

import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.InsightSource;
import com.datamaster.app.domain.QualityReport;
import com.datamaster.app.service.AiInsightService;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.SpreadsheetImportService;
import com.datamaster.app.service.WorkspacePersistenceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisControllerTest {
    @Test
    void regularInsightsUseSelectedMonthWithoutOverwritingTheCumulativeWorkspace() {
        AnalysisFixture fixture = analysisWithTwoMonths();
        AiInsightService ai = mock(AiInsightService.class);
        WorkspacePersistenceService workspace = mock(WorkspacePersistenceService.class);
        when(ai.generate(any(), eq("deepseek"))).thenReturn(generated());
        AnalysisController controller = controller(fixture.analysis(), ai, workspace);
        var request = new AnalysisController.InsightRequest(
                "deepseek", "month", List.of("2026"), List.of("2026-06"));

        var response = controller.insights(fixture.id(), request);

        verify(ai).generate(org.mockito.ArgumentMatchers.argThat(value ->
                value.rowCount() == 1
                        && value.summary().revenue().compareTo(new BigDecimal("600.00")) == 0),
                eq("deepseek"));
        verify(workspace, never()).refreshSnapshot(any());
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.summary().revenue()).isEqualByComparingTo("600.00");
        assertThat(response.insightSource()).isEqualTo(InsightSource.AI);
        assertThat(fixture.analysis().requireSession(fixture.id()).result().rowCount()).isEqualTo(2);
        assertThat(AnalysisController.shouldPersistInsights(request)).isFalse();
    }

    @Test
    void providerOnlyInsightRequestKeepsTheExistingPersistenceBehaviour() {
        AnalysisFixture fixture = analysisWithTwoMonths();
        AiInsightService ai = mock(AiInsightService.class);
        WorkspacePersistenceService workspace = mock(WorkspacePersistenceService.class);
        when(ai.generate(any(), eq("deepseek"))).thenReturn(generated());
        AnalysisController controller = controller(fixture.analysis(), ai, workspace);

        var response = controller.insights(fixture.id(), new AnalysisController.InsightRequest("deepseek"));

        assertThat(response.rowCount()).isEqualTo(2);
        assertThat(response.insightSource()).isEqualTo(InsightSource.AI);
        verify(workspace).refreshSnapshot(fixture.analysis().requireSession(fixture.id()));
        assertThat(AnalysisController.shouldPersistInsights(null)).isTrue();
    }

    private static AnalysisController controller(AnalysisService analysis, AiInsightService ai,
                                                 WorkspacePersistenceService workspace) {
        return new AnalysisController(null, analysis, ai, null, null, null, null, workspace);
    }

    private static AiInsightService.GenerationResult generated() {
        return new AiInsightService.GenerationResult(
                List.of(new Insight("期间结论", "仅使用所选期间", "复核期间", "EV-SUM-REV")),
                InsightSource.AI, "deepseek", "deepseek-chat", Instant.parse("2026-07-18T00:00:00Z"));
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
