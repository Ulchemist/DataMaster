package com.datamaster.app.api;

import com.datamaster.app.domain.SchemaSuggestionRequest;
import com.datamaster.app.domain.SchemaSuggestionResponse;
import com.datamaster.app.domain.ColumnProfile;
import com.datamaster.app.domain.DeepAnalysisResponse;
import com.datamaster.app.domain.FieldMapping;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.InsightSource;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.AiInsightService;
import com.datamaster.app.service.SchemaSuggestionService;
import com.datamaster.app.service.WorkspacePersistenceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AiAnalysisController {
    private final SchemaSuggestionService schemaSuggestions;
    private final AnalysisService analysis;
    private final AiInsightService aiInsights;
    private final WorkspacePersistenceService workspace;

    public AiAnalysisController(SchemaSuggestionService schemaSuggestions, AnalysisService analysis,
                                AiInsightService aiInsights, WorkspacePersistenceService workspace) {
        this.schemaSuggestions = schemaSuggestions;
        this.analysis = analysis;
        this.aiInsights = aiInsights;
        this.workspace = workspace;
    }

    @PostMapping(path = "/ai/schema-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SchemaSuggestionResponse schemaSuggestions(@RequestBody SchemaSuggestionRequest request) {
        return schemaSuggestions.suggest(request);
    }

    /**
     * Session-integrated entry point used after a real upload.  It derives the AI-safe request from
     * the stored aggregate profile and intentionally omits ColumnProfile.source.
     */
    @PostMapping(path = "/analysis/{id}/schema-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SchemaSuggestionResponse sessionSchemaSuggestions(@PathVariable String id,
                                                               @RequestBody ProviderRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择已配置的 AI 平台");
        var profile = analysis.requireSession(id).result().profile();
        SessionSchemaPayload payload = schemaPayload(request.providerId(), profile);
        return restoreLocalColumnIds(schemaSuggestions.suggest(payload.request()), payload.localColumnIds());
    }

    static SessionSchemaPayload schemaPayload(String providerId,
                                               com.datamaster.app.domain.AnalysisProfile profile) {
        List<SchemaSuggestionRequest.ColumnProfile> columns = new ArrayList<>();
        java.util.Map<String, String> localColumnIds = new java.util.LinkedHashMap<>();
        int aliasIndex = 0;
        for (ColumnProfile column : profile.columns()) {
            String aiColumnId = "col_" + (++aliasIndex);
            localColumnIds.put(aiColumnId, column.columnId());
            Set<SchemaSuggestionRequest.SemanticField> candidates = new LinkedHashSet<>();
            profile.mappings().stream()
                    .filter(mapping -> column.columnId().equals(mapping.columnId()))
                    .forEach(mapping -> addCandidateSemantics(candidates, mapping));
            BigDecimal numericCoverage = column.rowCount() <= 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(column.validNumericCount())
                    .divide(BigDecimal.valueOf(column.rowCount()), 4, java.math.RoundingMode.HALF_UP);
            // A mostly-text column can contain a handful of long digit strings (invoice notes,
            // concatenated identifiers, etc.).  Treating those as a high-precision financial
            // distribution both hurts schema reasoning and violates the public profile limits.
            boolean numericSignal = column.validNumericCount() > 0
                    && numericCoverage.compareTo(new BigDecimal("0.05")) >= 0;
            SchemaSuggestionRequest.NumericProfile numeric = !numericSignal ? null
                    : new SchemaSuggestionRequest.NumericProfile(schemaNumber(column.minimum()),
                    schemaNumber(column.maximum()), schemaNumber(column.mean()), null, null,
                    schemaNumber(column.negativeRatio()));
            columns.add(new SchemaSuggestionRequest.ColumnProfile(
                    aiColumnId, column.header(), inferType(column, numericCoverage), column.coverage(),
                    numeric, List.copyOf(candidates)));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("当前分析没有可供 AI 识别的列画像，请重新导入报表");
        }
        return new SessionSchemaPayload(new SchemaSuggestionRequest(providerId, columns),
                java.util.Map.copyOf(localColumnIds));
    }

    private static SchemaSuggestionResponse restoreLocalColumnIds(
            SchemaSuggestionResponse response, java.util.Map<String, String> localColumnIds) {
        List<SchemaSuggestionResponse.FieldCandidates> fields = response.fieldCandidates().stream()
                .map(value -> new SchemaSuggestionResponse.FieldCandidates(
                        localColumnIds.getOrDefault(value.columnId(), value.columnId()), value.candidates()))
                .toList();
        List<SchemaSuggestionResponse.Conflict> conflicts = response.conflicts().stream()
                .map(value -> new SchemaSuggestionResponse.Conflict(value.code(), value.columnIds().stream()
                        .map(id -> localColumnIds.getOrDefault(id, id)).toList(), value.semanticField(),
                        value.requiredConfirmation()))
                .toList();
        return new SchemaSuggestionResponse(fields, conflicts, response.requiredConfirmations(),
                response.limitations(), response.providerId(), response.model(), response.generatedAt());
    }

    @PostMapping(path = "/analysis/{id}/deep-insights", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeepAnalysisResponse deepInsights(@PathVariable String id, @RequestBody ProviderRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择已配置的 AI 平台");
        AnalysisSession liveSession = analysis.requireSession(id);
        AnalysisSession insightSession = deepInsightSession(analysis, liveSession, request);
        DeepAnalysisResponse generated = aiInsights.generateDeep(
                insightSession.result(), request.providerId());
        // A period-specific conclusion is valid only for that response scope. Persisting it into
        // the live session would make the next cumulative view present a monthly claim as the
        // workspace default. The unscoped path intentionally keeps the established behaviour.
        if (!shouldPersistDeepInsights(request)) return generated;
        List<Insight> persisted = generated.insights().stream().map(value -> new Insight(
                value.title(), value.finding(), value.action(), value.evidence().stream()
                .map(DeepAnalysisResponse.EvidenceReference::display).collect(Collectors.joining("；"))))
                .toList();
        analysis.updateInsights(id, persisted, InsightSource.AI,
                generated.providerId(), generated.model(), generated.generatedAt());
        workspace.refreshSnapshot(analysis.requireSession(id));
        return generated;
    }

    static AnalysisSession deepInsightSession(AnalysisService analysis, AnalysisSession liveSession,
                                              ProviderRequest request) {
        return analysis.scopedSession(liveSession, request.periodMode(), request.years(), request.months());
    }

    static boolean shouldPersistDeepInsights(ProviderRequest request) {
        return request != null && !request.hasExplicitPeriodFilter();
    }

    private static void addCandidateSemantics(Set<SchemaSuggestionRequest.SemanticField> result,
                                               FieldMapping mapping) {
        addSemantic(result, mapping.fieldId());
        mapping.alternatives().forEach(value -> addSemantic(result, value));
    }

    private static void addSemantic(Set<SchemaSuggestionRequest.SemanticField> result, String fieldId) {
        if (fieldId == null) return;
        String value = fieldId.toUpperCase(Locale.ROOT).split("__", 2)[0];
        SchemaSuggestionRequest.SemanticField semantic = switch (value) {
            case "DATE" -> SchemaSuggestionRequest.SemanticField.DATE;
            case "REVENUE" -> SchemaSuggestionRequest.SemanticField.REVENUE;
            case "COST" -> SchemaSuggestionRequest.SemanticField.COST;
            case "EXPENSE", "EXPENSES" -> SchemaSuggestionRequest.SemanticField.EXPENSE;
            case "PROFIT", "GROSS_PROFIT", "OPERATING_PROFIT" -> SchemaSuggestionRequest.SemanticField.PROFIT;
            case "QUANTITY" -> SchemaSuggestionRequest.SemanticField.QUANTITY;
            case "UNIT_PRICE", "PRICE" -> SchemaSuggestionRequest.SemanticField.UNIT_PRICE;
            case "PRODUCT" -> SchemaSuggestionRequest.SemanticField.PRODUCT;
            case "CUSTOMER", "CUSTOMER_DETAIL" -> SchemaSuggestionRequest.SemanticField.CUSTOMER;
            case "ORDER_ID", "ORDER_NUMBER" -> SchemaSuggestionRequest.SemanticField.ORDER_ID;
            case "REGION", "AREA" -> SchemaSuggestionRequest.SemanticField.REGION;
            case "CHANNEL" -> SchemaSuggestionRequest.SemanticField.CHANNEL;
            case "SALESPERSON", "SALES_GROUP", "DEPARTMENT" -> SchemaSuggestionRequest.SemanticField.SALESPERSON;
            case "DISCOUNT" -> SchemaSuggestionRequest.SemanticField.DISCOUNT;
            case "TAX" -> SchemaSuggestionRequest.SemanticField.TAX;
            default -> value.contains("CATEGORY") || value.equals("GRADE")
                    ? SchemaSuggestionRequest.SemanticField.CATEGORY
                    : null;
        };
        if (semantic != null) result.add(semantic);
    }

    private static SchemaSuggestionRequest.InferredType inferType(ColumnProfile column,
                                                                   BigDecimal numericCoverage) {
        String declared = column.inferredType() == null ? "" : column.inferredType().toUpperCase(Locale.ROOT);
        if (declared.contains("DATE") || declared.contains("TIME")) {
            return declared.contains("TIME") ? SchemaSuggestionRequest.InferredType.DATETIME
                    : SchemaSuggestionRequest.InferredType.DATE;
        }
        if (declared.contains("BOOL")) return SchemaSuggestionRequest.InferredType.BOOLEAN;
        if (numericCoverage.compareTo(new BigDecimal("0.90")) >= 0) {
            return SchemaSuggestionRequest.InferredType.DECIMAL;
        }
        if (numericCoverage.compareTo(BigDecimal.ZERO) > 0) return SchemaSuggestionRequest.InferredType.MIXED;
        if (declared.contains("TEXT") || declared.contains("STRING")) return SchemaSuggestionRequest.InferredType.TEXT;
        return SchemaSuggestionRequest.InferredType.UNKNOWN;
    }

    private static BigDecimal schemaNumber(BigDecimal value) {
        return value == null ? null : value.round(MathContext.DECIMAL64);
    }

    public record ProviderRequest(String providerId, String periodMode,
                                  List<String> years, List<String> months) {
        public ProviderRequest {
            years = years == null ? List.of() : List.copyOf(years);
            months = months == null ? List.of() : List.copyOf(months);
        }

        public ProviderRequest(String providerId) {
            this(providerId, null, List.of(), List.of());
        }

        boolean hasExplicitPeriodFilter() {
            return !years.isEmpty() || !months.isEmpty();
        }
    }

    record SessionSchemaPayload(SchemaSuggestionRequest request,
                                java.util.Map<String, String> localColumnIds) {
    }
}
