package com.datamaster.app.domain;

import java.time.Instant;
import java.util.List;

public record AnalysisResult(
        String id,
        int sourceFileCount,
        int rowCount,
        FinancialSummary summary,
        QualityReport quality,
        List<Breakdown> products,
        List<Breakdown> customers,
        List<MonthlyBreakdown> monthly,
        List<Insight> insights,
        InsightSource insightSource,
        String insightProviderId,
        String insightModel,
        Instant insightsGeneratedAt,
        AnalysisProfile profile,
        AnalysisCapabilities capabilities,
        List<DynamicBreakdown> dynamicBreakdowns
) {
    public AnalysisResult {
        products = products == null ? List.of() : List.copyOf(products);
        customers = customers == null ? List.of() : List.copyOf(customers);
        monthly = monthly == null ? List.of() : List.copyOf(monthly);
        insights = insights == null ? List.of() : List.copyOf(insights);
        insightSource = insightSource == null ? InsightSource.LOCAL_RULES : insightSource;
        if (insightSource == InsightSource.LOCAL_RULES) {
            insightProviderId = null;
            insightModel = null;
        }
        insightsGeneratedAt = insightsGeneratedAt == null ? Instant.now() : insightsGeneratedAt;
        profile = profile == null ? AnalysisProfile.empty() : profile;
        capabilities = capabilities == null ? AnalysisCapabilities.legacyComplete() : capabilities;
        dynamicBreakdowns = dynamicBreakdowns == null ? List.of() : List.copyOf(dynamicBreakdowns);
    }

    /** Backward-compatible constructor for existing integrations and tests. */
    public AnalysisResult(String id, int sourceFileCount, int rowCount, FinancialSummary summary,
                          QualityReport quality, List<Breakdown> products, List<Breakdown> customers,
                          List<MonthlyBreakdown> monthly, List<Insight> insights, InsightSource insightSource,
                          String insightProviderId, String insightModel, Instant insightsGeneratedAt) {
        this(id, sourceFileCount, rowCount, summary, quality, products, customers, monthly, insights,
                insightSource, insightProviderId, insightModel, insightsGeneratedAt,
                AnalysisProfile.empty(), AnalysisCapabilities.legacyComplete(), List.of());
    }

    public AnalysisResult withInsights(List<Insight> value, InsightSource source,
                                       String providerId, String model, Instant generatedAt) {
        return new AnalysisResult(id, sourceFileCount, rowCount, summary, quality,
                products, customers, monthly, value, source, providerId, model, generatedAt,
                profile, capabilities, dynamicBreakdowns);
    }
}
