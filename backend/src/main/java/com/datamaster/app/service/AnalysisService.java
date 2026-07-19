package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.AnalysisCapabilities;
import com.datamaster.app.domain.AnalysisProfile;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.DynamicBreakdown;
import com.datamaster.app.domain.FieldMapping;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.InsightSource;
import com.datamaster.app.domain.MonthlyBreakdown;
import com.datamaster.app.domain.QualityReport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

@Service
public class AnalysisService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    // The desktop UI releases the previous analysis after every successful replacement. Keep one
    // fallback session for an in-flight request, but do not retain four copies of a large workbook:
    // a 500 MiB transport limit must not turn into several gigabytes of avoidable row retention.
    private static final int MAX_RETAINED_SESSIONS = 2;
    private final Map<String, AnalysisSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> sessionOrder = new ConcurrentLinkedDeque<>();

    public AnalysisResult analyze(SpreadsheetImportService.ImportResult imported) {
        AnalysisProfile profile = imported.profile();
        AnalysisCapabilities capabilities = profile == null
                || (profile.mappings().isEmpty() && profile.columns().isEmpty())
                ? AnalysisCapabilities.legacyComplete() : capabilities(profile, imported.rows());
        return analyze(imported.rows(), imported.sourceFileCount(), imported.quality(), profile, capabilities);
    }

    public AnalysisSession requireSession(String id) {
        AnalysisSession session = sessions.get(id);
        if (session == null) {
            throw new AnalysisNotFoundException(id);
        }
        return session;
    }

    /** Re-registers a verified persisted session after application restart without recalculating it. */
    public AnalysisResult restoreSession(AnalysisSession session) {
        if (session == null || session.result() == null || session.result().id() == null
                || session.result().id().isBlank()) {
            throw new IllegalArgumentException("持久化分析快照缺少有效会话标识");
        }
        if (session.result().rowCount() != session.rows().size()) {
            throw new IllegalArgumentException("持久化分析快照的行数校验失败");
        }
        retainSession(session.result().id(), session);
        return session.result();
    }

    public void releaseSession(String id) {
        if (id == null || id.isBlank()) return;
        sessions.remove(id);
        sessionOrder.remove(id);
    }

    /** Builds an export-only analysis view over the selected years/months without registering a new session. */
    public AnalysisSession scopedSession(AnalysisSession source, String periodMode,
                                         List<String> years, List<String> months) {
        if (source == null) throw new IllegalArgumentException("分析会话不能为空");
        validatePeriodMode(periodMode);
        Set<String> selectedYears = normalizeYears(years);
        Set<String> selectedMonths = normalizeMonths(months);
        if (selectedYears.isEmpty() && selectedMonths.isEmpty()) return source;
        List<DataRow> scopedRows = source.rows().stream().filter(row -> {
            if (row.date() == null) return false;
            if (!selectedYears.isEmpty()
                    && !selectedYears.contains(String.valueOf(row.date().getYear()))) return false;
            return selectedMonths.isEmpty()
                    || selectedMonths.contains(YearMonth.from(row.date()).toString());
        }).toList();
        AnalysisResult original = source.result();
        AnalysisProfile profile = original.profile();
        AnalysisCapabilities scopedCapabilities = profile == null
                || (profile.mappings().isEmpty() && profile.columns().isEmpty())
                ? AnalysisCapabilities.legacyComplete() : capabilities(profile, scopedRows);
        FinancialSummary scopedSummary = summary(scopedRows, scopedCapabilities);
        List<Breakdown> scopedProducts = scopedCapabilities.hasProduct()
                ? breakdown(scopedRows, row -> label(row.product()), scopedCapabilities) : List.of();
        List<Breakdown> scopedCustomers = scopedCapabilities.hasCustomer()
                ? breakdown(scopedRows, row -> label(row.customer()), scopedCapabilities) : List.of();
        List<MonthlyBreakdown> scopedMonthly = scopedCapabilities.hasDate()
                ? monthly(scopedRows, scopedCapabilities) : List.of();
        List<DynamicBreakdown> scopedDynamic = dynamicBreakdowns(scopedRows, profile, scopedCapabilities);
        List<Insight> scopedInsights = localInsights(scopedSummary, scopedProducts, scopedCustomers,
                scopedCapabilities, profile);
        QualityReport sourceQuality = original.quality();
        Set<String> scopedSources = scopedRows.stream().map(DataRow::sourceFile)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> allSources = source.rows().stream().map(DataRow::sourceFile)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> scopedWarnings = sourceQuality.warnings().stream()
                .filter(warning -> scopedSources.stream().anyMatch(warning::contains))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (scopedSources.size() < allSources.size()) {
            scopedWarnings.add("期间范围提示：本报告仅使用 " + scopedSources.size() + " / "
                    + allSources.size() + " 个项目数据源；其他期间的数据质量告警未纳入本期间附录。");
        }
        QualityReport scopedQuality = new QualityReport(scopedRows.size(), scopedRows.size(),
                (int) scopedRows.stream().filter(row -> row.date() == null).count(),
                (int) scopedRows.stream().filter(row -> row.product() == null || row.product().isBlank()).count(),
                (int) scopedRows.stream().filter(row -> row.customer() == null || row.customer().isBlank()).count(),
                0, scopedWarnings, 0, 0);
        AnalysisResult scopedResult = new AnalysisResult(original.id(), scopedSources.size(), scopedRows.size(),
                scopedSummary, scopedQuality, scopedProducts, scopedCustomers, scopedMonthly, scopedInsights,
                InsightSource.LOCAL_RULES, null, null, Instant.now(), profile, scopedCapabilities, scopedDynamic);
        return new AnalysisSession(scopedResult, scopedRows);
    }

    private static void validatePeriodMode(String value) {
        if (value == null || value.isBlank()) return;
        String mode = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("cumulative", "year", "month").contains(mode)) {
            throw new IllegalArgumentException("periodMode“" + value
                    + "”不可用；可选值：cumulative、year、month");
        }
    }

    private static Set<String> normalizeYears(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null) continue;
            for (String part : raw.split(",")) {
                String value = part.strip();
                if (!value.matches("(?:19|20)\\d{2}")) {
                    throw new IllegalArgumentException("年度“" + value + "”格式无效，应为 YYYY");
                }
                result.add(value);
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizeMonths(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null) continue;
            for (String part : raw.split(",")) {
                try {
                    result.add(YearMonth.parse(part.strip()).toString());
                } catch (DateTimeParseException ex) {
                    throw new IllegalArgumentException("月度“" + part.strip() + "”格式无效，应为 YYYY-MM");
                }
            }
        }
        return Set.copyOf(result);
    }

    public AnalysisResult updateInsights(String id, List<Insight> insights, InsightSource source,
                                         String providerId, String model, Instant generatedAt) {
        return sessions.compute(id, (key, current) -> {
            if (current == null) throw new AnalysisNotFoundException(id);
            return current.withResult(current.result().withInsights(
                    insights, source, providerId, model, generatedAt));
        }).result();
    }

    private AnalysisResult analyze(List<DataRow> rows, int sourceCount, QualityReport quality,
                                   AnalysisProfile profile, AnalysisCapabilities capabilities) {
        String id = UUID.randomUUID().toString();
        FinancialSummary summary = summary(rows, capabilities);
        List<Breakdown> products = capabilities.hasProduct()
                ? breakdown(rows, row -> label(row.product()), capabilities) : List.of();
        List<Breakdown> customers = capabilities.hasCustomer()
                ? breakdown(rows, row -> label(row.customer()), capabilities) : List.of();
        List<MonthlyBreakdown> monthly = capabilities.hasDate() ? monthly(rows, capabilities) : List.of();
        List<DynamicBreakdown> dynamicBreakdowns = dynamicBreakdowns(rows, profile, capabilities);
        List<Insight> initialInsights = localInsights(summary, products, customers, capabilities, profile);
        AnalysisResult result = new AnalysisResult(id, sourceCount, rows.size(), summary, quality,
                products, customers, monthly, initialInsights, InsightSource.LOCAL_RULES,
                null, null, Instant.now(), profile, capabilities, dynamicBreakdowns);
        retainSession(id, new AnalysisSession(result, rows));
        return result;
    }

    private synchronized void retainSession(String id, AnalysisSession session) {
        sessions.put(id, session);
        sessionOrder.remove(id);
        sessionOrder.addLast(id);
        while (sessionOrder.size() > MAX_RETAINED_SESSIONS) {
            String expiredId = sessionOrder.pollFirst();
            if (expiredId != null) sessions.remove(expiredId);
        }
    }

    public static FinancialSummary summary(List<DataRow> rows) {
        return summary(rows, AnalysisCapabilities.legacyComplete());
    }

    private static FinancialSummary summary(List<DataRow> rows, AnalysisCapabilities capabilities) {
        Accumulator total = new Accumulator();
        rows.forEach(total::add);
        return total.summary(capabilities);
    }

    private static List<Breakdown> breakdown(List<DataRow> rows, Function<DataRow, String> classifier,
                                             AnalysisCapabilities capabilities) {
        Map<String, Accumulator> groups = new HashMap<>();
        for (DataRow row : rows) {
            groups.computeIfAbsent(classifier.apply(row), ignored -> new Accumulator()).add(row);
        }
        return groups.entrySet().stream()
                .map(entry -> entry.getValue().breakdown(entry.getKey(), capabilities))
                .sorted(Comparator.comparing(Breakdown::revenue).reversed().thenComparing(Breakdown::name))
                .toList();
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }

    private static List<MonthlyBreakdown> monthly(List<DataRow> rows, AnalysisCapabilities capabilities) {
        Map<String, Accumulator> groups = new HashMap<>();
        for (DataRow row : rows) {
            String month = row.date() == null ? "未指定日期" : YearMonth.from(row.date()).toString();
            groups.computeIfAbsent(month, ignored -> new Accumulator()).add(row);
        }
        return groups.entrySet().stream()
                .map(entry -> entry.getValue().monthly(entry.getKey(), capabilities))
                .sorted(Comparator.comparing(MonthlyBreakdown::month,
                        Comparator.comparing(value -> "未指定日期".equals(value) ? "9999-99" : value)))
                .toList();
    }

    private static AnalysisCapabilities capabilities(AnalysisProfile profile, List<DataRow> rows) {
        long revenueRows = rows.stream().filter(row -> row.metricValid("revenue")).count();
        long costRows = rows.stream().filter(row -> row.metricValid("cost")).count();
        long expenseRows = rows.stream().filter(row -> row.metricValid("expense")).count();
        long quantityRows = rows.stream().filter(row -> row.metricValid("quantity")).count();
        boolean revenueMappingBlocked = mappingBlocked(profile, "REVENUE");
        boolean costMappingBlocked = mappingBlocked(profile, "COST");
        boolean expenseMappingBlocked = mappingBlocked(profile, "EXPENSE");
        boolean revenueMapped = profile.hasSelected("revenue") && !revenueMappingBlocked;
        boolean costMapped = profile.hasSelected("cost") && !costMappingBlocked;
        boolean expenseMapped = profile.hasSelected("expense") && !expenseMappingBlocked;
        boolean hasRevenue = revenueMapped && revenueRows > 0;
        boolean hasCost = costMapped && costRows > 0;
        boolean hasExpenses = expenseMapped && expenseRows > 0;
        boolean hasQuantity = profile.hasSelected("quantity") && quantityRows > 0;
        boolean hasDate = profile.hasSelected("date") && rows.stream().anyMatch(row -> row.date() != null);
        boolean hasProduct = profile.hasSelected("product")
                && rows.stream().anyMatch(row -> row.product() != null && !row.product().isBlank());
        boolean hasCustomer = profile.hasSelected("customer")
                && rows.stream().anyMatch(row -> row.customer() != null && !row.customer().isBlank());

        long grossRows = rows.stream().filter(row -> row.metricValid("revenue") && row.metricValid("cost")).count();
        long operatingRows = rows.stream().filter(row -> row.metricValid("revenue")
                && row.metricValid("cost") && row.metricValid("expense")).count();
        BigDecimal allRevenue = rows.stream().filter(row -> row.metricValid("revenue"))
                .map(row -> row.revenue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossComparableRevenue = rows.stream().filter(row -> row.metricValid("revenue")
                        && row.metricValid("cost"))
                .map(row -> row.revenue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal operatingComparableRevenue = rows.stream().filter(row -> row.metricValid("revenue")
                        && row.metricValid("cost") && row.metricValid("expense"))
                .map(row -> row.revenue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossRowCoverage = ratio(grossRows, revenueRows);
        BigDecimal operatingRowCoverage = ratio(operatingRows, revenueRows);
        BigDecimal grossRevenueCoverage = allRevenue.signum() == 0 && revenueRows > 0
                ? grossRowCoverage : ratio(grossComparableRevenue, allRevenue);
        BigDecimal operatingRevenueCoverage = allRevenue.signum() == 0 && revenueRows > 0
                ? operatingRowCoverage : ratio(operatingComparableRevenue, allRevenue);

        boolean grossAvailable = hasRevenue && hasCost && grossRows > 0
                && grossRevenueCoverage.compareTo(new BigDecimal("0.80")) >= 0;
        boolean operatingAvailable = grossAvailable && hasExpenses && operatingRows > 0
                && operatingRevenueCoverage.compareTo(new BigDecimal("0.80")) >= 0;
        List<String> reasons = new ArrayList<>();
        if (!hasRevenue) reasons.add(revenueMappingBlocked
                ? "至少一份数据源无法应用已确认的收入口径，收入与利润结论已停用"
                : revenueMapped ? "收入字段没有可解析数值，无法计算经营金额" : "未确认收入字段，无法计算经营金额");
        if (!hasCost) reasons.add(costMappingBlocked
                ? "至少一份数据源无法应用已确认的成本口径，毛利与毛利率不可用"
                : costMapped ? "成本字段没有可解析数值，毛利与毛利率不可用" : "未确认成本口径，毛利与毛利率不可用");
        if (hasCost && grossRevenueCoverage.compareTo(new BigDecimal("0.80")) < 0) {
            reasons.add("收入与成本同时有效的毛利可算收入覆盖率不足 80%，总览毛利结论已停用");
        }
        if (!hasExpenses) reasons.add(expenseMappingBlocked
                ? "至少一份数据源无法应用已确认的期间费用口径，经营利润与经营利润率不可用"
                : expenseMapped ? "期间费用字段没有可解析数值，经营利润与经营利润率不可用"
                : "未识别或未确认期间费用，经营利润与经营利润率不可用");
        if (hasExpenses && operatingRevenueCoverage.compareTo(new BigDecimal("0.80")) < 0) {
            reasons.add("收入、成本和费用同时有效的经营利润可算收入覆盖率不足 80%，经营利润结论已停用");
        }
        return new AnalysisCapabilities(hasRevenue, hasCost, hasExpenses, hasQuantity, hasDate,
                hasProduct, hasCustomer, grossAvailable, operatingAvailable,
                decimal(profile.coverageOf("revenue")), decimal(profile.coverageOf("cost")),
                decimal(profile.coverageOf("expense")), grossRowCoverage, operatingRowCoverage,
                grossRevenueCoverage, operatingRevenueCoverage,
                Math.max(0, revenueRows - grossRows), Math.max(0, revenueRows - operatingRows),
                money(allRevenue.subtract(grossComparableRevenue)),
                money(allRevenue.subtract(operatingComparableRevenue)), reasons);
    }

    private static boolean mappingBlocked(AnalysisProfile profile, String semantic) {
        return profile.mappingIssues().stream().anyMatch(issue -> {
            if (!"BLOCKING".equalsIgnoreCase(issue.severity())) return false;
            String code = issue.code() == null ? "" : issue.code().toUpperCase(java.util.Locale.ROOT);
            return code.equals("AMBIGUOUS_" + semantic)
                    || code.equals("MAPPING_OVERRIDE_NOT_FOUND_" + semantic)
                    || code.equals("MAPPING_OVERRIDE_NOT_FOUND");
        });
    }

    private static List<DynamicBreakdown> dynamicBreakdowns(List<DataRow> rows, AnalysisProfile profile,
                                                            AnalysisCapabilities capabilities) {
        if (profile == null || profile.availableDimensions().isEmpty()) return List.of();
        Map<String, String> labels = new LinkedHashMap<>();
        profile.mappings().stream().filter(FieldMapping::selected)
                .filter(value -> value.role().endsWith("DIMENSION"))
                .forEach(value -> labels.putIfAbsent(value.fieldId(), value.header()));
        List<DynamicBreakdown> result = new ArrayList<>();
        for (Map.Entry<String, String> dimension : labels.entrySet()) {
            if ("product".equals(dimension.getKey()) || "customer".equals(dimension.getKey())) continue;
            List<DataRow> available = rows.stream().filter(row -> {
                String value = row.dimensions().get(dimension.getKey());
                return value != null && !value.isBlank();
            }).toList();
            if (available.isEmpty()) continue;
            List<Breakdown> values = breakdown(available,
                    row -> label(row.dimensions().get(dimension.getKey())), capabilities);
            int totalGroups = values.size();
            int limit = Math.min(100, totalGroups);
            result.add(new DynamicBreakdown(dimension.getKey(), dimension.getValue(),
                    values.subList(0, limit), totalGroups, totalGroups > limit));
        }
        return List.copyOf(result);
    }

    private static List<Insight> localInsights(FinancialSummary summary, List<Breakdown> products,
                                               List<Breakdown> customers,
                                               AnalysisCapabilities capabilities,
        AnalysisProfile profile) {
        if (capabilities.operatingProfitAvailable()) {
            return OfflineInsightService.generate(summary, products, customers);
        }
        List<Insight> result = new ArrayList<>();
        if (!capabilities.hasRevenue()) {
            result.add(new Insight("先确认收入口径",
                    "当前报表存在多套收入候选，确认前不生成销售额、利润或扭亏结论。",
                    "在字段映射中选择本次分析采用的未税、含税或调整后收入列，再重新计算。",
                    profile.mappingIssues().stream().filter(value -> "AMBIGUOUS_REVENUE".equals(value.code()))
                            .findFirst().map(value -> value.candidates().size() + " 个收入候选待确认")
                            .orElse("未确认收入字段")));
        } else if (!capabilities.hasCost()) {
            result.add(new Insight("先确认利润口径",
                    "当前报表可以完成销售分析，但存在多个成本候选或未提供成本字段。",
                    "在字段映射中选择业务采用的成本口径后重新计算；确认前不展示毛利和经营利润。",
                    profile.mappingIssues().stream().filter(value -> "AMBIGUOUS_COST".equals(value.code()))
                            .findFirst().map(value -> value.candidates().size() + " 个成本候选待确认")
                            .orElse("未确认成本字段")));
        } else if (capabilities.grossProfitAvailable()) {
            result.add(new Insight("当前仅支持毛利分析",
                    "收入和成本口径已确认，但报表没有可用的期间费用口径。",
                    "先使用毛利和毛利率评估产品、客户及渠道；补充期间费用后再计算经营利润。",
                    "毛利可算收入覆盖率 " + percent(capabilities.grossProfitRevenueCoverage())));
        } else {
            result.add(new Insight("修复金额字段后再看利润",
                    "收入与成本的同行有效覆盖不足，当前不具备代表整体业务的利润结论。",
                    "修复公式错误或空值后重新导入；系统不会把错误成本按 0 参与毛利。",
                    "排除 " + capabilities.grossProfitExcludedRows() + " 行，涉及收入 "
                            + moneyText(capabilities.grossProfitExcludedRevenue())));
        }
        if (capabilities.hasRevenue() && !products.isEmpty()) {
            Breakdown top = products.get(0);
            String productAction = capabilities.hasCost()
                    ? "让 AI 结合动态维度分析高低毛利组合；期间费用未确认前不要推断经营利润。"
                    : "让 AI 结合动态维度分析高低收入贡献组合；成本未确认前不要推断毛利或经营利润。";
            result.add(new Insight("优先复盘高收入产品「" + top.name() + "」",
                    "该产品收入贡献最高，可先从销量、区域和渠道结构解释销售表现。",
                    productAction,
                    "产品收入 " + moneyText(top.revenue())));
        }
        return List.copyOf(result.subList(0, Math.min(4, result.size())));
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(4);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return BigDecimal.ZERO.setScale(4);
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String moneyText(BigDecimal value) {
        return "¥" + money(value).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static final class Accumulator {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal expenses = BigDecimal.ZERO;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal absoluteRevenue = BigDecimal.ZERO;
        private BigDecimal grossComparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal operatingComparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal grossComparableRevenue = BigDecimal.ZERO;
        private BigDecimal grossComparableCost = BigDecimal.ZERO;
        private BigDecimal operatingComparableRevenue = BigDecimal.ZERO;
        private BigDecimal operatingComparableCost = BigDecimal.ZERO;
        private BigDecimal operatingComparableExpenses = BigDecimal.ZERO;
        private long validRevenueRows;
        private long grossComparableRows;
        private long operatingComparableRows;

        private void add(DataRow row) {
            if (row.metricValid("revenue")) {
                revenue = revenue.add(row.revenue());
                absoluteRevenue = absoluteRevenue.add(row.revenue().abs());
                validRevenueRows++;
            }
            if (row.metricValid("cost")) cost = cost.add(row.cost());
            if (row.metricValid("expense")) expenses = expenses.add(row.expense());
            if (row.metricValid("quantity")) quantity = quantity.add(row.quantity());
            if (row.metricValid("revenue") && row.metricValid("cost")) {
                grossComparableRows++;
                grossComparableRevenue = grossComparableRevenue.add(row.revenue());
                grossComparableAbsoluteRevenue = grossComparableAbsoluteRevenue.add(row.revenue().abs());
                grossComparableCost = grossComparableCost.add(row.cost());
            }
            if (row.metricValid("revenue") && row.metricValid("cost") && row.metricValid("expense")) {
                operatingComparableRows++;
                operatingComparableRevenue = operatingComparableRevenue.add(row.revenue());
                operatingComparableAbsoluteRevenue = operatingComparableAbsoluteRevenue.add(row.revenue().abs());
                operatingComparableCost = operatingComparableCost.add(row.cost());
                operatingComparableExpenses = operatingComparableExpenses.add(row.expense());
            }
        }

        private FinancialSummary summary(AnalysisCapabilities capabilities) {
            BigDecimal roundedRevenue = capabilities.hasRevenue() ? money(revenue) : ZERO;
            BigDecimal roundedCost = capabilities.hasCost() ? money(cost) : ZERO;
            BigDecimal grossProfit = capabilities.grossProfitAvailable()
                    ? money(grossComparableRevenue.subtract(grossComparableCost)) : ZERO;
            BigDecimal roundedExpenses = capabilities.hasExpenses() ? money(expenses) : ZERO;
            BigDecimal operatingProfit = capabilities.operatingProfitAvailable()
                    ? money(operatingComparableRevenue.subtract(operatingComparableCost)
                    .subtract(operatingComparableExpenses)) : ZERO;
            return new FinancialSummary(roundedRevenue, roundedCost,
                    capabilities.grossProfitAvailable() ? money(grossComparableRevenue) : ZERO,
                    capabilities.grossProfitAvailable() ? money(grossComparableCost) : ZERO,
                    grossProfit, roundedExpenses,
                    capabilities.operatingProfitAvailable() ? money(operatingComparableRevenue) : ZERO,
                    capabilities.operatingProfitAvailable() ? money(operatingComparableCost) : ZERO,
                    capabilities.operatingProfitAvailable() ? money(operatingComparableExpenses) : ZERO,
                    operatingProfit,
                    capabilities.grossProfitAvailable() ? ratio(grossProfit, money(grossComparableRevenue)) : ZERO,
                    capabilities.operatingProfitAvailable()
                            ? ratio(operatingProfit, money(operatingComparableRevenue)) : ZERO);
        }

        private Breakdown breakdown(String name, AnalysisCapabilities global) {
            BigDecimal grossRevenueCoverage = absoluteRevenue.signum() == 0 && validRevenueRows > 0
                    ? AnalysisService.ratio(grossComparableRows, validRevenueRows)
                    : ratio(grossComparableAbsoluteRevenue, absoluteRevenue);
            BigDecimal operatingRevenueCoverage = absoluteRevenue.signum() == 0 && validRevenueRows > 0
                    ? AnalysisService.ratio(operatingComparableRows, validRevenueRows)
                    : ratio(operatingComparableAbsoluteRevenue, absoluteRevenue);
            boolean grossAvailable = global.hasRevenue() && global.hasCost() && grossComparableRows > 0
                    && grossRevenueCoverage.compareTo(new BigDecimal("0.80")) >= 0;
            boolean operatingAvailable = grossAvailable && global.hasExpenses() && operatingComparableRows > 0
                    && operatingRevenueCoverage.compareTo(new BigDecimal("0.80")) >= 0;
            AnalysisCapabilities local = new AnalysisCapabilities(global.hasRevenue(), global.hasCost(),
                    global.hasExpenses(), global.hasQuantity(), global.hasDate(), global.hasProduct(),
                    global.hasCustomer(), grossAvailable, operatingAvailable,
                    global.revenueCoverage(), global.costCoverage(), global.expenseCoverage(),
                    AnalysisService.ratio(grossComparableRows, validRevenueRows),
                    AnalysisService.ratio(operatingComparableRows, validRevenueRows),
                    grossRevenueCoverage, operatingRevenueCoverage,
                    Math.max(0, validRevenueRows - grossComparableRows),
                    Math.max(0, validRevenueRows - operatingComparableRows),
                    money(absoluteRevenue.subtract(grossComparableAbsoluteRevenue)),
                    money(absoluteRevenue.subtract(operatingComparableAbsoluteRevenue)), List.of());
            FinancialSummary value = summary(local);
            BigDecimal grossMargin = grossAvailable && grossComparableRevenue.signum() > 0
                    ? ratio(value.grossProfit(), money(grossComparableRevenue)) : null;
            BigDecimal operatingMargin = operatingAvailable && operatingComparableRevenue.signum() > 0
                    ? ratio(value.operatingProfit(), money(operatingComparableRevenue)) : null;
            return new Breakdown(name, value.revenue(), value.cost(), value.grossProfit(), value.expenses(),
                    value.operatingProfit(), grossMargin, operatingMargin, money(quantity),
                    grossAvailable, operatingAvailable, local.grossProfitCoverage(),
                    local.operatingProfitCoverage(), local.grossProfitExcludedRows(),
                    local.operatingProfitExcludedRows());
        }

        private MonthlyBreakdown monthly(String month, AnalysisCapabilities global) {
            Breakdown value = breakdown(month, global);
            return new MonthlyBreakdown(month, value.revenue(), value.cost(), value.grossProfit(), value.expenses(),
                    value.operatingProfit(), value.grossMargin(), value.operatingMargin(), money(quantity),
                    value.grossProfitAvailable(), value.operatingProfitAvailable(),
                    value.grossProfitCoverage(), value.operatingProfitCoverage(),
                    value.grossProfitExcludedRows(), value.operatingProfitExcludedRows());
        }

        private static BigDecimal money(BigDecimal value) {
            return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
        }

        private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
            if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO.setScale(4);
            }
            return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
        }
    }

    public static final class AnalysisNotFoundException extends RuntimeException {
        public AnalysisNotFoundException(String id) {
            super("分析任务不存在或服务已重启：" + id);
        }
    }
}
