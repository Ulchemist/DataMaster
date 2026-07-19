package com.datamaster.app.api;

import com.datamaster.app.domain.PdfReportRequest;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.PdfReportService;
import com.datamaster.app.support.AnalysisFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerTest {
    @Test
    void getShortcutForwardsTheSelectedPeriodToThePdfGenerator() {
        AnalysisService analysis = new AnalysisService();
        var fixture = AnalysisFixtures.completeAnalysis(analysis);
        PdfReportService reports = mock(PdfReportService.class);
        when(reports.create(any(), any())).thenReturn("%PDF-test".getBytes(StandardCharsets.US_ASCII));
        ReportController controller = new ReportController(analysis, reports);

        var response = controller.complete(fixture.id(), "month", List.of("2026"), List.of("2026-06"));

        ArgumentCaptor<PdfReportRequest> request = ArgumentCaptor.forClass(PdfReportRequest.class);
        verify(reports).create(any(), request.capture());
        assertThat(request.getValue().periodMode()).isEqualTo("month");
        assertThat(request.getValue().years()).containsExactly("2026");
        assertThat(request.getValue().months()).containsExactly("2026-06");
        assertThat(response.getBody()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void getShortcutRemainsCumulativeWhenPeriodParametersAreMissing() {
        PdfReportRequest request = ReportController.completeRequest(null, null, null);

        assertThat(request.periodMode()).isEqualTo("cumulative");
        assertThat(request.years()).isEmpty();
        assertThat(request.months()).isEmpty();
        assertThat(request.sections()).contains("overview", "product", "customer", "profit");
    }
}
