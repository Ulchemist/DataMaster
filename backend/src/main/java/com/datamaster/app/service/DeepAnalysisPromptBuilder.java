package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisCapabilities;
import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.DynamicBreakdown;
import com.datamaster.app.domain.MappingIssue;
import com.datamaster.app.domain.MonthlyBreakdown;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds a bounded, aggregate-only prompt and an evidence allowlist for AI interpretation. */
final class DeepAnalysisPromptBuilder {
    private static final int MAX_DIMENSIONS = 12;
    private static final int MAX_GROUPS = 10;
    private static final int MAX_REVENUE_DRIVERS = 5;
    private static final int MAX_GROSS_PROFIT_DRAGS = 5;
    private static final String COMMERCIAL_DISCOUNT_ADJUSTMENT = "商业折扣";
    private static final String DIRECT_COUNTER_TOKEN = "直营专柜";
    private static final String ADJUSTMENT_TOKEN = "调整";
    private static final Set<String> PRODUCT_DIMENSION_TOKENS = Set.of(
            "product", "productname", "产品", "产品名称", "商品", "商品名称", "品名", "物料名称");

    private DeepAnalysisPromptBuilder() {
    }

    static PromptContext build(AnalysisResult result) {
        AnalysisCapabilities capabilities = result.capabilities();
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "DEEP_BUSINESS_INTERPRETATION");
        payload.put("privacy", Map.of(
                "rawRowsIncluded", false,
                "fileNamesIncluded", false,
                "entityNamesIncluded", false,
                "groupLabelsAreAnonymous", true
        ));
        payload.put("capabilities", capabilityPayload(capabilities));
        payload.put("dataQuality", qualityPayload(result, evidence));
        payload.put("mappingIssues", mappingIssuePayload(result, evidence));

        List<Map<String, Object>> summaries = new ArrayList<>();
        addSummaryMetrics(result, capabilities, summaries, evidence);
        payload.put("summaryMetrics", summaries);

        List<Map<String, Object>> breakdowns = new ArrayList<>();
        Set<String> usedDimensions = new LinkedHashSet<>();
        for (DynamicBreakdown value : result.dynamicBreakdowns()) {
            if (breakdowns.size() == MAX_DIMENSIONS) break;
            boolean productDimension = isProductDimension(value.dimensionId(), value.dimensionLabel());
            List<Breakdown> dimensionValues = productDimension
                    ? productAnalysisValues(value.values()) : value.values();
            if (productDimension && dimensionValues.isEmpty()) continue;
            String dimensionId = productDimension ? "product"
                    : safeId(value.dimensionId(), "dimension_" + (breakdowns.size() + 1));
            if (!usedDimensions.add(dimensionId)) continue;
            int excludedAdjustments = value.values().size() - dimensionValues.size();
            int totalGroups = productDimension
                    ? Math.max(dimensionValues.size(), value.totalGroups() - excludedAdjustments)
                    : value.totalGroups();
            breakdowns.add(dimensionPayload(dimensionId, value.dimensionLabel(), dimensionValues, totalGroups,
                    value.truncated(), capabilities, evidence));
        }
        List<Breakdown> productValues = productAnalysisValues(result.products());
        if (capabilities.hasProduct() && !productValues.isEmpty() && usedDimensions.add("product")) {
            breakdowns.add(dimensionPayload("product", "产品", productValues, productValues.size(),
                    productValues.size() > MAX_GROUPS, capabilities, evidence));
        }
        if (capabilities.hasCustomer() && !result.customers().isEmpty() && usedDimensions.add("customer")) {
            breakdowns.add(dimensionPayload("customer", "客户", result.customers(), result.customers().size(),
                    result.customers().size() > MAX_GROUPS, capabilities, evidence));
        }
        payload.put("dynamicBreakdowns", breakdowns);

        if (capabilities.hasDate() && !result.monthly().isEmpty()) {
            payload.put("timeSeries", monthlyPayload(result.monthly(), capabilities, evidence));
        } else {
            payload.put("timeSeries", List.of());
        }
        payload.put("anomalies", anomalyPayload(result, capabilities, breakdowns, evidence));
        addLegacyEvidenceAliases(result, capabilities, evidence);
        payload.put("allowedEvidenceIds", List.copyOf(evidence.keySet()));
        payload.put("claimTypeAllowlist", List.of(ClaimType.values()));
        payload.put("categoryAllowlist", List.of(InsightCategory.values()));
        return new PromptContext(Map.copyOf(payload), Map.copyOf(evidence), capabilities);
    }

    private static Map<String, Object> capabilityPayload(AnalysisCapabilities value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasRevenue", value.hasRevenue());
        result.put("hasCost", value.hasCost());
        result.put("hasExpenses", value.hasExpenses());
        result.put("hasQuantity", value.hasQuantity());
        result.put("hasDate", value.hasDate());
        result.put("hasProduct", value.hasProduct());
        result.put("hasCustomer", value.hasCustomer());
        result.put("grossProfitAvailable", value.grossProfitAvailable());
        result.put("operatingProfitAvailable", value.operatingProfitAvailable());
        result.put("revenueCoverage", value.revenueCoverage());
        result.put("costCoverage", value.costCoverage());
        result.put("expenseCoverage", value.expenseCoverage());
        result.put("grossProfitComparableRowCoverage", value.grossProfitCoverage());
        result.put("operatingProfitComparableRowCoverage", value.operatingProfitCoverage());
        result.put("grossProfitComparableRevenueCoverage", value.grossProfitRevenueCoverage());
        result.put("operatingProfitComparableRevenueCoverage", value.operatingProfitRevenueCoverage());
        result.put("grossProfitExcludedRows", value.grossProfitExcludedRows());
        result.put("operatingProfitExcludedRows", value.operatingProfitExcludedRows());
        result.put("grossProfitExcludedRevenue", value.grossProfitExcludedRevenue());
        result.put("operatingProfitExcludedRevenue", value.operatingProfitExcludedRevenue());
        result.put("unavailableReasonCount", value.unavailableReasons().size());
        return result;
    }

    private static Map<String, Object> qualityPayload(AnalysisResult result, Map<String, Evidence> evidence) {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("totalRows", result.quality().totalRows());
        quality.put("validRows", result.quality().validRows());
        quality.put("missingDate", result.quality().missingDate());
        quality.put("missingProduct", result.quality().missingProduct());
        quality.put("missingCustomer", result.quality().missingCustomer());
        quality.put("invalidNumericCells", result.quality().invalidNumericCells());
        quality.put("profiledNumericErrors", result.quality().profiledNumericErrors());
        quality.put("externalWorkbookLinks", result.quality().externalWorkbookLinks());
        quality.put("warningCount", result.quality().warnings().size());
        addEvidence(evidence, "quality.invalidNumericCells", "数据质量", "无效数值单元格 "
                + result.quality().invalidNumericCells() + " 个", MetricKind.DATA_QUALITY);
        if (result.quality().profiledNumericErrors() > 0) {
            addEvidence(evidence, "quality.profiledNumericErrors", "候选口径质量",
                    "所有候选指标列共发现 " + result.quality().profiledNumericErrors()
                            + " 个不可解析值；未选口径未进入当前计算",
                    MetricKind.DATA_QUALITY);
        }
        if (result.quality().externalWorkbookLinks() > 0) {
            addEvidence(evidence, "quality.externalWorkbookLinks", "外部链接风险",
                    "工作簿包含 " + result.quality().externalWorkbookLinks()
                            + " 个外部工作簿链接，当前分析使用文件内缓存值",
                    MetricKind.DATA_QUALITY);
        }
        return quality;
    }

    private static List<Map<String, Object>> mappingIssuePayload(AnalysisResult result,
                                                                 Map<String, Evidence> evidence) {
        List<Map<String, Object>> issues = new ArrayList<>();
        int index = 0;
        for (MappingIssue issue : result.profile().mappingIssues()) {
            if (index == 30) break;
            index++;
            String evidenceId = "mapping.issue." + index;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("issueId", evidenceId);
            value.put("severity", safeCode(issue.severity(), "WARNING"));
            value.put("code", safeCode(issue.code(), "MAPPING_UNCERTAIN"));
            value.put("candidateCount", issue.candidates().size());
            issues.add(value);
            addEvidence(evidence, evidenceId, "字段映射", safeLocalText(issue.message(), "存在字段映射问题"),
                    MetricKind.DATA_QUALITY);
        }
        return List.copyOf(issues);
    }

    private static void addSummaryMetrics(AnalysisResult result, AnalysisCapabilities capabilities,
                                          List<Map<String, Object>> target, Map<String, Evidence> evidence) {
        if (capabilities.hasRevenue()) {
            addMetric(target, evidence, "summary.revenue", "revenue", result.summary().revenue(), "营业收入",
                    MetricKind.REVENUE);
        }
        if (capabilities.hasCost()) {
            addMetric(target, evidence, "summary.cost", "cost", result.summary().cost(), "营业成本",
                    MetricKind.COST);
        }
        if (capabilities.grossProfitAvailable()) {
            addComparableSummaryMetric(target, evidence, "summary.grossProfit", "grossProfit",
                    result.summary().grossProfit(), "毛利", MetricKind.GROSS_PROFIT,
                    capabilities.grossProfitCoverage(), capabilities.grossProfitRevenueCoverage(),
                    capabilities.grossProfitExcludedRows(), capabilities.grossProfitExcludedRevenue());
            addComparableSummaryMetric(target, evidence, "summary.grossMargin", "grossMargin",
                    result.summary().grossMargin(), "毛利率", MetricKind.GROSS_PROFIT,
                    capabilities.grossProfitCoverage(), capabilities.grossProfitRevenueCoverage(),
                    capabilities.grossProfitExcludedRows(), capabilities.grossProfitExcludedRevenue());
        }
        if (capabilities.hasExpenses()) {
            addMetric(target, evidence, "summary.expenses", "expenses", result.summary().expenses(), "期间费用",
                    MetricKind.EXPENSE);
        }
        if (capabilities.operatingProfitAvailable()) {
            addComparableSummaryMetric(target, evidence, "summary.operatingProfit", "operatingProfit",
                    result.summary().operatingProfit(), "经营利润", MetricKind.OPERATING_PROFIT,
                    capabilities.operatingProfitCoverage(), capabilities.operatingProfitRevenueCoverage(),
                    capabilities.operatingProfitExcludedRows(), capabilities.operatingProfitExcludedRevenue());
            addComparableSummaryMetric(target, evidence, "summary.operatingMargin", "operatingMargin",
                    result.summary().operatingMargin(), "经营利润率", MetricKind.OPERATING_PROFIT,
                    capabilities.operatingProfitCoverage(), capabilities.operatingProfitRevenueCoverage(),
                    capabilities.operatingProfitExcludedRows(), capabilities.operatingProfitExcludedRevenue());
        }
    }

    private static Map<String, Object> dimensionPayload(String dimensionId, String localDimensionLabel,
                                                         List<Breakdown> values, int totalGroups, boolean truncated,
                                                         AnalysisCapabilities capabilities,
                                                         Map<String, Evidence> evidence) {
        Map<String, Object> dimension = new LinkedHashMap<>();
        dimension.put("dimensionId", dimensionId);
        dimension.put("totalGroups", totalGroups);
        List<SelectedBreakdown> selected = selectBreakdowns(values, capabilities);
        dimension.put("truncated", truncated || values.size() > selected.size());
        dimension.put("selectionPolicy", Map.of(
                "revenueDrivers", "TOP_REVENUE",
                "grossProfitDrags", capabilities.grossProfitAvailable()
                        ? "LOWEST_COMPARABLE_GROSS_PROFIT" : "UNAVAILABLE"
        ));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (SelectedBreakdown selectedValue : selected) {
            Breakdown value = selectedValue.value();
            String groupId = selectedValue.revenueRank() != null
                    ? dimensionId + "_revenue_rank_" + selectedValue.revenueRank()
                    : dimensionId + "_gross_drag_rank_" + selectedValue.grossProfitDragRank();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("groupId", groupId);
            List<String> selectionReasons = new ArrayList<>(2);
            if (selectedValue.revenueRank() != null) {
                group.put("revenueRank", selectedValue.revenueRank());
                selectionReasons.add("TOP_REVENUE");
            }
            if (selectedValue.grossProfitDragRank() != null) {
                group.put("grossProfitDragRank", selectedValue.grossProfitDragRank());
                selectionReasons.add("BOTTOM_GROSS_PROFIT");
            }
            group.put("selectionReasons", selectionReasons);
            List<Map<String, Object>> metrics = new ArrayList<>();
            if (capabilities.hasRevenue()) {
                addBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId, value.name(),
                        "revenue", value.revenue(), MetricKind.REVENUE);
            }
            if (capabilities.hasCost()) {
                addBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId, value.name(),
                        "cost", value.cost(), MetricKind.COST);
            }
            if (capabilities.grossProfitAvailable() && value.grossProfitAvailable()) {
                addComparableBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId,
                        value.name(), "grossProfit", value.grossProfit(), MetricKind.GROSS_PROFIT,
                        value.grossProfitCoverage(), value.grossProfitExcludedRows());
                if (value.grossMargin() != null) {
                    addComparableBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId,
                            value.name(), "grossMargin", value.grossMargin(), MetricKind.GROSS_PROFIT,
                            value.grossProfitCoverage(), value.grossProfitExcludedRows());
                }
            }
            if (capabilities.hasExpenses()) {
                addBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId, value.name(),
                        "expenses", value.expenses(), MetricKind.EXPENSE);
            }
            if (capabilities.operatingProfitAvailable() && value.operatingProfitAvailable()) {
                addComparableBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId,
                        value.name(), "operatingProfit", value.operatingProfit(), MetricKind.OPERATING_PROFIT,
                        value.operatingProfitCoverage(), value.operatingProfitExcludedRows());
                if (value.operatingMargin() != null) {
                    addComparableBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId,
                            value.name(), "operatingMargin", value.operatingMargin(), MetricKind.OPERATING_PROFIT,
                            value.operatingProfitCoverage(), value.operatingProfitExcludedRows());
                }
            }
            if (capabilities.hasQuantity()) {
                addBreakdownMetric(metrics, evidence, dimensionId, localDimensionLabel, groupId, value.name(),
                        "quantity", value.quantity(), MetricKind.QUANTITY);
            }
            group.put("metrics", metrics);
            groups.add(group);
        }
        dimension.put("groups", groups);
        return dimension;
    }

    /**
     * A revenue-sorted source alone hides small-revenue groups with large losses. Keep both views in the bounded
     * prompt: the five largest revenue contributors and, where comparable gross profit exists, the five lowest
     * gross-profit groups. Group labels remain only in the local evidence catalog and are never placed in payload.
     */
    private static List<SelectedBreakdown> selectBreakdowns(List<Breakdown> values,
                                                            AnalysisCapabilities capabilities) {
        if (values == null || values.isEmpty()) return List.of();
        List<Breakdown> revenueDrivers = values.stream()
                .sorted(Comparator.comparing(Breakdown::revenue).reversed())
                .limit(Math.min(MAX_REVENUE_DRIVERS, MAX_GROUPS))
                .toList();
        List<Breakdown> grossProfitDrags = capabilities.grossProfitAvailable()
                ? values.stream()
                .filter(Breakdown::grossProfitAvailable)
                .sorted(Comparator.comparing(Breakdown::grossProfit)
                        .thenComparing(Breakdown::revenue, Comparator.reverseOrder()))
                .limit(Math.min(MAX_GROSS_PROFIT_DRAGS, MAX_GROUPS))
                .toList()
                : List.of();

        LinkedHashSet<Breakdown> ordered = new LinkedHashSet<>();
        ordered.addAll(revenueDrivers);
        ordered.addAll(grossProfitDrags);
        return ordered.stream().limit(MAX_GROUPS).map(value -> {
            int revenueIndex = revenueDrivers.indexOf(value);
            int dragIndex = grossProfitDrags.indexOf(value);
            return new SelectedBreakdown(value,
                    revenueIndex < 0 ? null : revenueIndex + 1,
                    dragIndex < 0 ? null : dragIndex + 1);
        }).toList();
    }

    private static List<Map<String, Object>> monthlyPayload(List<MonthlyBreakdown> values,
                                                             AnalysisCapabilities capabilities,
                                                             Map<String, Evidence> evidence) {
        List<Map<String, Object>> months = new ArrayList<>();
        int index = 0;
        for (MonthlyBreakdown value : values.stream().limit(36).toList()) {
            index++;
            String periodId = "period_" + index;
            Map<String, Object> month = new LinkedHashMap<>();
            month.put("periodId", periodId);
            month.put("sequence", index);
            List<Map<String, Object>> metrics = new ArrayList<>();
            if (capabilities.hasRevenue()) {
                addTimeMetric(metrics, evidence, periodId, value.month(), "revenue", value.revenue(),
                        MetricKind.REVENUE);
            }
            if (capabilities.hasCost()) {
                addTimeMetric(metrics, evidence, periodId, value.month(), "cost", value.cost(), MetricKind.COST);
            }
            if (capabilities.grossProfitAvailable() && value.grossProfitAvailable()) {
                addComparableTimeMetric(metrics, evidence, periodId, value.month(), "grossProfit",
                        value.grossProfit(), MetricKind.GROSS_PROFIT, value.grossProfitCoverage(),
                        value.grossProfitExcludedRows());
            }
            if (capabilities.hasExpenses()) {
                addTimeMetric(metrics, evidence, periodId, value.month(), "expenses", value.expenses(),
                        MetricKind.EXPENSE);
            }
            if (capabilities.operatingProfitAvailable() && value.operatingProfitAvailable()) {
                addComparableTimeMetric(metrics, evidence, periodId, value.month(), "operatingProfit",
                        value.operatingProfit(), MetricKind.OPERATING_PROFIT, value.operatingProfitCoverage(),
                        value.operatingProfitExcludedRows());
            }
            if (capabilities.hasQuantity()) {
                addTimeMetric(metrics, evidence, periodId, value.month(), "quantity", value.quantity(),
                        MetricKind.QUANTITY);
            }
            month.put("metrics", metrics);
            months.add(month);
        }
        return months;
    }

    private static List<Map<String, Object>> anomalyPayload(AnalysisResult result,
                                                             AnalysisCapabilities capabilities,
                                                             List<Map<String, Object>> breakdowns,
                                                             Map<String, Evidence> evidence) {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        if (capabilities.operatingProfitAvailable()
                && result.summary().operatingProfit().compareTo(BigDecimal.ZERO) < 0) {
            anomalies.add(anomaly("NEGATIVE_OPERATING_PROFIT", "HIGH", "summary.operatingProfit"));
        }
        if (capabilities.grossProfitAvailable() && result.summary().grossProfit().compareTo(BigDecimal.ZERO) < 0) {
            anomalies.add(anomaly("NEGATIVE_GROSS_PROFIT", "HIGH", "summary.grossProfit"));
        }
        if (capabilities.hasRevenue() && result.monthly().size() >= 2) {
            MonthlyBreakdown first = result.monthly().get(0);
            MonthlyBreakdown last = result.monthly().get(result.monthly().size() - 1);
            if (last.revenue().compareTo(first.revenue()) < 0) {
                String id = "trend.revenue.first_to_last";
                addEvidence(evidence, id, "收入趋势", "首期 " + money(first.revenue()) + "，末期 "
                        + money(last.revenue()), MetricKind.REVENUE);
                anomalies.add(anomaly("REVENUE_DECLINE_FIRST_TO_LAST", "MEDIUM", id));
            }
        }
        if (capabilities.hasRevenue() && result.summary().revenue().compareTo(BigDecimal.ZERO) > 0) {
            for (DynamicBreakdown breakdown : result.dynamicBreakdowns().stream().limit(MAX_DIMENSIONS).toList()) {
                List<Breakdown> values = isProductDimension(breakdown.dimensionId(), breakdown.dimensionLabel())
                        ? productAnalysisValues(breakdown.values()) : breakdown.values();
                if (values.isEmpty()) continue;
                Breakdown top = values.get(0);
                BigDecimal share = top.revenue().divide(result.summary().revenue(), 4, RoundingMode.HALF_UP);
                if (share.compareTo(new BigDecimal("0.50")) >= 0) {
                    String dimensionId = isProductDimension(breakdown.dimensionId(), breakdown.dimensionLabel())
                            ? "product" : safeId(breakdown.dimensionId(), "dimension");
                    String id = "anomaly." + dimensionId + ".topRevenueShare";
                    addEvidence(evidence, id, "集中度", safeLocalText(breakdown.dimensionLabel(), "维度")
                            + "首位「" + safeLocalText(top.name(), "未命名") + "」收入占比 " + percent(share),
                            MetricKind.CONTRIBUTION);
                    anomalies.add(anomaly("TOP_GROUP_REVENUE_CONCENTRATION", "MEDIUM", id));
                }
            }
        }
        if (result.quality().invalidNumericCells() > 0) {
            anomalies.add(anomaly("INVALID_NUMERIC_CELLS", "MEDIUM", "quality.invalidNumericCells"));
        }
        if (result.quality().profiledNumericErrors() > result.quality().invalidNumericCells()) {
            anomalies.add(anomaly("PROFILED_CANDIDATE_ERRORS", "LOW", "quality.profiledNumericErrors"));
        }
        if (result.quality().externalWorkbookLinks() > 0) {
            anomalies.add(anomaly("EXTERNAL_WORKBOOK_LINKS", "MEDIUM", "quality.externalWorkbookLinks"));
        }
        return anomalies;
    }

    private static Map<String, Object> anomaly(String code, String severity, String evidenceId) {
        return Map.of("code", code, "severity", severity, "evidenceId", evidenceId);
    }

    private static void addLegacyEvidenceAliases(AnalysisResult result, AnalysisCapabilities capabilities,
                                                  Map<String, Evidence> evidence) {
        alias(evidence, "revenue", "summary.revenue");
        alias(evidence, "cost", "summary.cost");
        alias(evidence, "grossProfit", "summary.grossProfit");
        alias(evidence, "expenses", "summary.expenses");
        alias(evidence, "operatingProfit", "summary.operatingProfit");
        alias(evidence, "grossMargin", "summary.grossMargin");
        alias(evidence, "operatingMargin", "summary.operatingMargin");
        if (capabilities.operatingProfitAvailable() && capabilities.hasProduct()) {
            productAnalysisValues(result.products()).stream()
                    .filter(Breakdown::operatingProfitAvailable)
                    .min(java.util.Comparator.comparing(Breakdown::operatingProfit))
                    .ifPresent(value -> addEvidence(evidence, "worstProductOperatingProfit", "产品利润",
                            "最低经营利润产品「" + safeLocalText(value.name(), "未命名") + "」经营利润 "
                                    + money(value.operatingProfit()), MetricKind.OPERATING_PROFIT));
        }
        if (capabilities.hasRevenue() && capabilities.hasCustomer() && !result.customers().isEmpty()) {
            Breakdown value = result.customers().get(0);
            addEvidence(evidence, "topCustomerRevenue", "客户收入",
                    "最大收入客户「" + safeLocalText(value.name(), "未命名") + "」收入 " + money(value.revenue()),
                    MetricKind.REVENUE);
        }
    }

    private static void alias(Map<String, Evidence> evidence, String alias, String target) {
        Evidence value = evidence.get(target);
        if (value != null) evidence.put(alias, new Evidence(alias, value.label(), value.display(), value.kind()));
    }

    private static void addMetric(List<Map<String, Object>> target, Map<String, Evidence> evidence,
                                  String evidenceId, String metric, BigDecimal value, String localLabel,
                                  MetricKind kind) {
        target.add(Map.of("evidenceId", evidenceId, "metric", metric, "value", value));
        addEvidence(evidence, evidenceId, localLabel, localLabel + " " + displayValue(metric, value), kind);
    }

    private static void addComparableSummaryMetric(List<Map<String, Object>> target,
                                                    Map<String, Evidence> evidence, String evidenceId,
                                                    String metric, BigDecimal value, String localLabel,
                                                    MetricKind kind, BigDecimal rowCoverage,
                                                    BigDecimal revenueCoverage, long excludedRows,
                                                    BigDecimal excludedRevenue) {
        Map<String, Object> metricValue = new LinkedHashMap<>();
        metricValue.put("evidenceId", evidenceId);
        metricValue.put("metric", metric);
        metricValue.put("value", value);
        metricValue.put("basis", "COMPARABLE_ROWS_ONLY");
        metricValue.put("comparableRowCoverage", rowCoverage);
        metricValue.put("comparableRevenueCoverage", revenueCoverage);
        metricValue.put("excludedRows", excludedRows);
        metricValue.put("excludedRevenue", excludedRevenue);
        target.add(metricValue);
        addEvidence(evidence, evidenceId, localLabel,
                localLabel + " " + displayValue(metric, value) + "（仅基于收入与所需字段同时有效的可比行；"
                        + "收入覆盖 " + percent(revenueCoverage) + "，排除 " + excludedRows + " 行）", kind);
    }

    private static void addBreakdownMetric(List<Map<String, Object>> target, Map<String, Evidence> evidence,
                                           String dimensionId, String localDimensionLabel, String groupId,
                                           String localGroupName, String metric, BigDecimal value, MetricKind kind) {
        String evidenceId = "breakdown." + dimensionId + "." + groupId + "." + metric;
        target.add(Map.of("evidenceId", evidenceId, "metric", metric, "value", value));
        String label = safeLocalText(localDimensionLabel, dimensionId) + "「"
                + safeLocalText(localGroupName, "未命名") + "」";
        addEvidence(evidence, evidenceId, label, label + metricLabel(metric) + " " + displayValue(metric, value), kind);
    }

    private static void addComparableBreakdownMetric(List<Map<String, Object>> target,
                                                       Map<String, Evidence> evidence, String dimensionId,
                                                       String localDimensionLabel, String groupId,
                                                       String localGroupName, String metric, BigDecimal value,
                                                       MetricKind kind, BigDecimal rowCoverage,
                                                       long excludedRows) {
        String evidenceId = "breakdown." + dimensionId + "." + groupId + "." + metric;
        Map<String, Object> metricValue = new LinkedHashMap<>();
        metricValue.put("evidenceId", evidenceId);
        metricValue.put("metric", metric);
        metricValue.put("value", value);
        metricValue.put("basis", "COMPARABLE_ROWS_ONLY");
        metricValue.put("comparableRowCoverage", rowCoverage);
        metricValue.put("excludedRows", excludedRows);
        target.add(metricValue);
        String label = safeLocalText(localDimensionLabel, dimensionId) + "「"
                + safeLocalText(localGroupName, "未命名") + "」";
        addEvidence(evidence, evidenceId, label,
                label + metricLabel(metric) + " " + displayValue(metric, value)
                        + "（可比行覆盖 " + percent(rowCoverage) + "，排除 " + excludedRows + " 行）", kind);
    }

    private static void addTimeMetric(List<Map<String, Object>> target, Map<String, Evidence> evidence,
                                      String periodId, String localMonth, String metric, BigDecimal value,
                                      MetricKind kind) {
        String evidenceId = "timeseries." + periodId + "." + metric;
        target.add(Map.of("evidenceId", evidenceId, "metric", metric, "value", value));
        addEvidence(evidence, evidenceId, "期间趋势", safeLocalText(localMonth, "期间")
                + metricLabel(metric) + " " + displayValue(metric, value), kind);
    }

    private static void addComparableTimeMetric(List<Map<String, Object>> target,
                                                 Map<String, Evidence> evidence, String periodId,
                                                 String localMonth, String metric, BigDecimal value,
                                                 MetricKind kind, BigDecimal rowCoverage, long excludedRows) {
        String evidenceId = "timeseries." + periodId + "." + metric;
        Map<String, Object> metricValue = new LinkedHashMap<>();
        metricValue.put("evidenceId", evidenceId);
        metricValue.put("metric", metric);
        metricValue.put("value", value);
        metricValue.put("basis", "COMPARABLE_ROWS_ONLY");
        metricValue.put("comparableRowCoverage", rowCoverage);
        metricValue.put("excludedRows", excludedRows);
        target.add(metricValue);
        addEvidence(evidence, evidenceId, "期间趋势", safeLocalText(localMonth, "期间")
                + metricLabel(metric) + " " + displayValue(metric, value) + "（可比行覆盖 "
                + percent(rowCoverage) + "，排除 " + excludedRows + " 行）", kind);
    }

    private static void addEvidence(Map<String, Evidence> evidence, String id, String label, String display,
                                    MetricKind kind) {
        evidence.putIfAbsent(id, new Evidence(id, label, display, kind));
    }

    private static String safeId(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_+", "_");
        if (safe.isBlank()) return fallback;
        return safe.length() <= 48 ? safe : safe.substring(0, 48);
    }

    private static String safeCode(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
        return safe.isBlank() ? fallback : safe.substring(0, Math.min(64, safe.length()));
    }

    private static String safeLocalText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        return clean.substring(0, Math.min(120, clean.length()));
    }

    private static List<Breakdown> productAnalysisValues(List<Breakdown> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(value -> !isProfitAdjustmentProduct(value.name())).toList();
    }

    static boolean isProfitAdjustmentProduct(String value) {
        String normalized = normalizedToken(value);
        return COMMERCIAL_DISCOUNT_ADJUSTMENT.equals(normalized)
                || (normalized.contains(DIRECT_COUNTER_TOKEN) && normalized.contains(ADJUSTMENT_TOKEN));
    }

    private static boolean isProductDimension(String dimensionId, String dimensionLabel) {
        return PRODUCT_DIMENSION_TOKENS.contains(normalizedToken(dimensionId))
                || PRODUCT_DIMENSION_TOKENS.contains(normalizedToken(dimensionLabel));
    }

    private static String normalizedToken(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String metricLabel(String metric) {
        return switch (metric) {
            case "revenue" -> "收入";
            case "cost" -> "成本";
            case "grossProfit" -> "毛利";
            case "grossMargin" -> "毛利率";
            case "expenses" -> "期间费用";
            case "operatingProfit" -> "经营利润";
            case "operatingMargin" -> "经营利润率";
            case "quantity" -> "数量";
            default -> metric;
        };
    }

    private static String displayValue(String metric, BigDecimal value) {
        return metric.endsWith("Margin") ? percent(value) : money(value);
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : "¥" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? "—" : value.multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP) + "%";
    }

    record PromptContext(Map<String, Object> payload, Map<String, Evidence> evidence,
                         AnalysisCapabilities capabilities) {
    }

    record Evidence(String id, String label, String display, MetricKind kind) {
    }

    private record SelectedBreakdown(Breakdown value, Integer revenueRank, Integer grossProfitDragRank) {
    }

    enum ClaimType {
        FACT, CONTRIBUTION, HYPOTHESIS
    }

    enum InsightCategory {
        REVENUE, TREND, PRODUCT, CUSTOMER, COST, EXPENSE, PROFIT, DATA_QUALITY, OPERATIONS
    }

    enum MetricKind {
        REVENUE, COST, EXPENSE, GROSS_PROFIT, OPERATING_PROFIT, QUANTITY, CONTRIBUTION, DATA_QUALITY
    }
}
