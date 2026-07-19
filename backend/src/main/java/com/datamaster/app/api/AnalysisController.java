package com.datamaster.app.api;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.AnalysisChatRequest;
import com.datamaster.app.domain.AnalysisChatResponse;
import com.datamaster.app.domain.ExploreRequest;
import com.datamaster.app.domain.ExploreResponse;
import com.datamaster.app.service.AnalysisChatService;
import com.datamaster.app.service.AiInsightService;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.ExportService;
import com.datamaster.app.service.ExploreService;
import com.datamaster.app.service.SpreadsheetImportService;
import com.datamaster.app.service.WorkspacePersistenceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final SpreadsheetImportService importer;
    private final AnalysisService analysis;
    private final AiInsightService ai;
    private final ExportService exporter;
    private final ObjectMapper objectMapper;
    private final ExploreService explorer;
    private final AnalysisChatService chat;
    private final WorkspacePersistenceService workspace;

    public AnalysisController(SpreadsheetImportService importer, AnalysisService analysis,
                              AiInsightService ai, ExportService exporter, ObjectMapper objectMapper,
                              ExploreService explorer, AnalysisChatService chat,
                              WorkspacePersistenceService workspace) {
        this.importer = importer;
        this.analysis = analysis;
        this.ai = ai;
        this.exporter = exporter;
        this.objectMapper = objectMapper;
        this.explorer = explorer;
        this.chat = chat;
        this.workspace = workspace;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalysisResult upload(@RequestPart("files") MultipartFile[] files,
                                 @RequestPart(value = "mapping", required = false) String mappingJson) {
        Map<String, String> mapping = parseMapping(mappingJson);
        AnalysisResult result = analysis.analyze(importer.importFiles(files, mapping));
        workspace.save(analysis.requireSession(result.id()), files, mapping);
        return result;
    }

    private Map<String, String> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(mappingJson, new TypeReference<>() { });
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("字段映射 JSON 格式无效，请重新选择字段后再试", ex);
        }
    }

    @PostMapping(path = "/{id}/insights", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisResult insights(@PathVariable String id, @RequestBody(required = false) InsightRequest request) {
        AnalysisSession liveSession = analysis.requireSession(id);
        AnalysisSession scopedSession = insightSession(analysis, liveSession, request);
        String providerId = request == null ? null : request.providerId();
        AiInsightService.GenerationResult generated = ai.generate(scopedSession.result(), providerId);
        // A selected month/year is an ephemeral view. Persisting its claims into the cumulative
        // workspace would make the next launch show period-specific advice as the default result.
        if (!shouldPersistInsights(request)) {
            return scopedSession.result().withInsights(generated.insights(), generated.source(),
                    generated.providerId(), generated.model(), generated.generatedAt());
        }
        AnalysisResult updated = analysis.updateInsights(id, generated.insights(), generated.source(),
                generated.providerId(), generated.model(), generated.generatedAt());
        workspace.refreshSnapshot(analysis.requireSession(id));
        return updated;
    }

    static AnalysisSession insightSession(AnalysisService analysis, AnalysisSession liveSession,
                                          InsightRequest request) {
        if (request == null) return liveSession;
        return analysis.scopedSession(liveSession, request.periodMode(), request.years(), request.months());
    }

    static boolean shouldPersistInsights(InsightRequest request) {
        return request == null || !request.hasExplicitPeriodFilter();
    }

    @PostMapping(path = "/{id}/explore", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExploreResponse explore(@PathVariable String id, @RequestBody(required = false) ExploreRequest request) {
        return explorer.explore(id, analysis.requireSession(id), request);
    }

    @PostMapping(path = "/{id}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisChatResponse chat(@PathVariable String id, @RequestBody AnalysisChatRequest request) {
        return chat.chat(id, analysis.requireSession(id), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> release(@PathVariable String id) {
        analysis.releaseSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/export.xlsx")
    public ResponseEntity<byte[]> excel(@PathVariable String id,
                                        @RequestParam(required = false) String periodMode,
                                        @RequestParam(required = false) List<String> years,
                                        @RequestParam(required = false) List<String> months) {
        AnalysisSession session = analysis.scopedSession(analysis.requireSession(id), periodMode, years, months);
        return attachment(exporter.excel(session, periodMode), XLSX,
                "DataMaster-经营分析-" + shortId(id) + ".xlsx");
    }

    @GetMapping("/{id}/report.docx")
    public ResponseEntity<byte[]> word(@PathVariable String id,
                                       @RequestParam(required = false) String periodMode,
                                       @RequestParam(required = false) List<String> years,
                                       @RequestParam(required = false) List<String> months) {
        AnalysisSession session = analysis.scopedSession(analysis.requireSession(id), periodMode, years, months);
        return attachment(exporter.word(session, periodMode), DOCX,
                "DataMaster-经营分析报告-" + shortId(id) + ".docx");
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes, MediaType contentType, String filename) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public record InsightRequest(String providerId, String periodMode,
                                 List<String> years, List<String> months) {
        public InsightRequest {
            years = years == null ? List.of() : List.copyOf(years);
            months = months == null ? List.of() : List.copyOf(months);
        }

        /** Backwards-compatible constructor for existing clients that only send the provider. */
        public InsightRequest(String providerId) {
            this(providerId, null, List.of(), List.of());
        }

        boolean hasExplicitPeriodFilter() {
            return !years.isEmpty() || !months.isEmpty();
        }
    }
}
