package com.datamaster.app.api;

import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.PdfReportRequest;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.PdfReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class ReportController {
    private static final MediaType PDF = MediaType.parseMediaType("application/pdf");

    private final AnalysisService analysis;
    private final PdfReportService reports;

    public ReportController(AnalysisService analysis, PdfReportService reports) {
        this.analysis = analysis;
        this.reports = reports;
    }

    /** Complete-report shortcut, useful for direct links and smoke tests. */
    @GetMapping("/{id}/report.pdf")
    public ResponseEntity<byte[]> complete(@PathVariable String id,
                                           @RequestParam(required = false) String periodMode,
                                           @RequestParam(required = false) List<String> years,
                                           @RequestParam(required = false) List<String> months) {
        return response(id, completeRequest(periodMode, years, months));
    }

    static PdfReportRequest completeRequest(String periodMode, List<String> years, List<String> months) {
        PdfReportRequest defaults = PdfReportRequest.complete();
        String mode = periodMode == null || periodMode.isBlank() ? defaults.periodMode() : periodMode;
        return new PdfReportRequest(defaults.title(), defaults.sections(), defaults.topN(),
                mode, years, months);
    }

    /** Interactive export entry point used by the report-scope dialog. */
    @PostMapping(path = "/{id}/report.pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> selected(@PathVariable String id,
                                           @RequestBody(required = false) PdfReportRequest request) {
        return response(id, request == null ? PdfReportRequest.complete() : request);
    }

    private ResponseEntity<byte[]> response(String id, PdfReportRequest request) {
        AnalysisSession session = analysis.requireSession(id);
        byte[] bytes = reports.create(session, request);
        String shortId = id.length() <= 8 ? id : id.substring(0, 8);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("DataMaster-经营分析报告-" + shortId + ".pdf", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }
}
