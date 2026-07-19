package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.ExploreRequest;
import com.datamaster.app.domain.ExploreResponse;
import com.datamaster.app.domain.FieldMapping;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** General purpose product/customer/organisation drill-down over locally retained rows. */
@Service
public class ExploreService {
    private static final int DEFAULT_LIMIT = 20;
    /** A page is deliberately bounded; callers can traverse every group with offset pagination. */
    private static final int MAX_LIMIT = 500;
    private static final int MAX_SEARCH_LENGTH = 120;
    private static final int MAX_FILTER_VALUES = 500;
    private static final List<String> PREFERRED_DIMENSIONS = List.of(
            "year", "month",
            "product", "customer", "customerAnalysisCategory", "customerAnalysisLargeCategory",
            "businessAnalysisCategory", "businessAnalysisLargeCategory", "productForm",
            "region", "department", "salesGroup", "channel", "customerDetail", "deliveryAddress",
            "productSpec"
    );
    private static final Map<String, String> STANDARD_LABELS = Map.ofEntries(
            Map.entry("product", "产品"),
            Map.entry("customer", "客户"),
            Map.entry("year", "年度"),
            Map.entry("month", "月度"),
            Map.entry("customerAnalysisCategory", "客户分析分类"),
            Map.entry("customerAnalysisLargeCategory", "客户分析大类"),
            Map.entry("businessAnalysisCategory", "经营分析分类"),
            Map.entry("businessAnalysisLargeCategory", "经营分析大类"),
            Map.entry("productForm", "产品形态（汇报）"),
            Map.entry("region", "销售区域"),
            Map.entry("department", "销售部门"),
            Map.entry("salesGroup", "销售组"),
            Map.entry("channel", "渠道"),
            Map.entry("customerDetail", "送货客户"),
            Map.entry("deliveryAddress", "送货地址"),
            Map.entry("productSpec", "规格型号")
    );
    private static final Set<String> QUANTITY_METRICS = Set.of(
            "quantity", "outboundQuantity", "convertedQuantity");
    private static final Set<String> COST_METRICS = Set.of("cost", "transferCost");
    private static final Set<String> SORT_METRICS = Set.of("revenue", "quantity", "unitPrice", "profit");
    private static final Map<String, String> METRIC_ALIASES = Map.ofEntries(
            Map.entry("数量", "quantity"), Map.entry("销售数量", "quantity"),
            Map.entry("出库数量", "outboundQuantity"), Map.entry("换算只数", "convertedQuantity"),
            Map.entry("成本", "cost"), Map.entry("营业成本", "cost"),
            Map.entry("调拨成本", "transferCost"), Map.entry("调拨成本（含运费）", "transferCost"),
            Map.entry("调拨成本(含运费)", "transferCost")
    );
    private static final int MAX_METADATA_CACHE = 4;
    private final Map<String, SessionMetadata> metadataCache = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SessionMetadata> eldest) {
                    return size() > MAX_METADATA_CACHE;
                }
            });

    public ExploreResponse explore(String analysisId, AnalysisSession session, ExploreRequest request) {
        if (session == null) throw new IllegalArgumentException("分析会话不能为空");
        ExploreRequest safeRequest = request == null
                ? new ExploreRequest(null, Map.of(), null, null, null, null) : request;
        List<DataRow> rows = session.rows();
        SessionMetadata metadata = metadata(analysisId, session);
        List<String> availableYears = metadata.availableYears();
        List<String> availableMonths = metadata.availableMonths();
        PeriodSelection period = normalizePeriodSelection(safeRequest, availableYears, availableMonths);
        List<DataRow> periodRows = period.unrestricted()
                ? rows : rows.stream().filter(period::matches).toList();
        LinkedHashMap<String, String> labels = new LinkedHashMap<>(metadata.labels());
        List<String> dimensions = metadata.dimensions();
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("当前报表没有可用于分组或筛选的维度");
        }
        String requestedGroup = clean(safeRequest.groupBy());
        String groupBy = requestedGroup == null
                ? (dimensions.contains("product") ? "product" : dimensions.get(0))
                : resolveDimension(requestedGroup, dimensions, labels, "分组字段");

        Map<String, List<String>> filters = normalizeFilters(safeRequest.filters(), dimensions, labels);
        List<String> quantityMetrics = availableMetrics(periodRows, QUANTITY_METRICS,
                List.of("convertedQuantity", "outboundQuantity", "quantity"));
        List<String> costMetrics = availableMetrics(periodRows, COST_METRICS, List.of("cost", "transferCost"));
        String quantityMetric = selectMetric(safeRequest.quantityMetric(), quantityMetrics, "数量口径");
        String costMetric = selectMetric(safeRequest.costMetric(), costMetrics, "成本口径");
        String sortBy = selectSortMetric(safeRequest.sortBy(), quantityMetric);
        String search = clean(safeRequest.search());
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search 最多 " + MAX_SEARCH_LENGTH + " 个字符");
        }
        String profitFilter = selectProfitFilter(safeRequest.profitFilter());
        int offset = safeRequest.offset() == null ? 0 : safeRequest.offset();
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能小于 0");
        }
        int limit = safeRequest.limit() == null ? DEFAULT_LIMIT : safeRequest.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_LIMIT + " 之间");
        }

        Map<String, List<String>> filterOptions = safeRequest.wantsFilterOptions()
                ? withDisplayAliases(filterOptions(periodRows, dimensions), labels)
                : Map.of();
        Map<String, Aggregate> groups = new LinkedHashMap<>();
        Aggregate totals = new Aggregate();
        for (DataRow row : periodRows) {
            if (!matches(row, filters)) continue;
            totals.add(row, quantityMetric, costMetric);
            String group = label(value(row, groupBy));
            groups.computeIfAbsent(group, ignored -> new Aggregate()).add(row, quantityMetric, costMetric);
        }

        ExploreResponse.ExploreItem totalItem = totals.item("__total__", "合计",
                totals.revenueAvailable ? totals.revenue : null);
        BigDecimal shareDenominator = totalItem.revenue();
        List<ExploreResponse.ExploreItem> allItems = groups.entrySet().stream()
                .map(entry -> entry.getValue().item(entry.getKey(), entry.getKey(), shareDenominator))
                .filter(item -> fuzzyMatches(item.name(), search))
                .filter(item -> matchesProfitFilter(item, profitFilter))
                .sorted(itemComparator(sortBy))
                .toList();
        int totalGroups = allItems.size();
        int start = Math.min(offset, totalGroups);
        int end = Math.min(start + limit, totalGroups);
        List<ExploreResponse.ExploreItem> items = allItems.subList(start, end);
        ExploreResponse.PeriodSummary periodSummary = periodSummary(totalItem, totals, quantityMetric);
        return new ExploreResponse(analysisId, groupBy, labels, dimensions, quantityMetrics, costMetrics,
                quantityMetric, costMetric, period.mode(), period.years(), period.months(),
                availableYears, availableMonths, filters, filterOptions, periodSummary,
                totalItem, items, search, profitFilter,
                offset, limit,
                items.size(), totalGroups, totalGroups > items.size(), end < totalGroups,
                metadata.drillSuggestions());
    }

    private static ExploreResponse.PeriodSummary periodSummary(ExploreResponse.ExploreItem total,
                                                                 Aggregate aggregate,
                                                                 String quantityMetric) {
        return new ExploreResponse.PeriodSummary(total.records(), total.revenue(), total.cost(), total.expense(),
                total.comparableRevenue(), total.comparableCost(), total.profit(), total.margin(),
                total.profitCoverage(), total.profitAvailable(), total.operatingComparableRevenue(),
                total.operatingComparableCost(), total.operatingComparableExpense(), total.operatingProfit(),
                total.operatingMargin(), total.operatingProfitCoverage(), total.operatingProfitAvailable(),
                total.quantity(), total.salesQuantity(), total.convertedQuantity(), quantityMetric,
                aggregate.revenueAvailable, aggregate.costAvailable, aggregate.expenseAvailable,
                aggregate.quantityAvailable, aggregate.comparableRows, aggregate.operatingComparableRows,
                Math.max(0, aggregate.revenueRows - aggregate.comparableRows),
                Math.max(0, aggregate.revenueRows - aggregate.operatingComparableRows),
                money(aggregate.absoluteRevenue.subtract(aggregate.comparableAbsoluteRevenue)),
                money(aggregate.absoluteRevenue.subtract(aggregate.operatingComparableAbsoluteRevenue)));
    }

    private static LinkedHashMap<String, String> dimensionLabels(AnalysisSession session) {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>(STANDARD_LABELS);
        if (session.result().profile() != null) {
            session.result().profile().mappings().stream().filter(FieldMapping::selected)
                    .filter(mapping -> mapping.role().endsWith("DIMENSION"))
                    .forEach(mapping -> labels.put(mapping.fieldId(), mapping.header()));
        }
        return labels;
    }

    private SessionMetadata metadata(String analysisId, AnalysisSession session) {
        String key = present(analysisId) ? analysisId : session.result().id();
        synchronized (metadataCache) {
            SessionMetadata cached = metadataCache.get(key);
            if (cached != null) return cached;
            List<DataRow> rows = session.rows();
            LinkedHashMap<String, String> labels = dimensionLabels(session);
            List<String> dimensions = availableDimensions(rows, labels);
            SessionMetadata created = new SessionMetadata(availableYears(rows), availableMonths(rows),
                    Collections.unmodifiableMap(new LinkedHashMap<>(labels)), dimensions,
                    drillSuggestions(rows, dimensions));
            metadataCache.put(key, created);
            return created;
        }
    }

    private static List<String> availableDimensions(List<DataRow> rows, Map<String, String> labels) {
        Set<String> found = new HashSet<>();
        for (DataRow row : rows) {
            if (row.date() != null) {
                found.add("year");
                found.add("month");
            }
            if (present(row.product())) found.add("product");
            if (present(row.customer())) found.add("customer");
            row.dimensions().forEach((key, value) -> {
                if (present(key) && present(value)) found.add(key);
            });
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        PREFERRED_DIMENSIONS.stream().filter(found::contains).forEach(ordered::add);
        labels.keySet().stream().filter(found::contains).forEach(ordered::add);
        found.stream().sorted().forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static List<String> availableMetrics(List<DataRow> rows, Set<String> allowlist,
                                                  List<String> order) {
        Set<String> found = new HashSet<>();
        for (DataRow row : rows) {
            for (String metric : allowlist) {
                if (row.metricValid(metric)) found.add(metric);
            }
        }
        return order.stream().filter(found::contains).toList();
    }

    private static String selectMetric(String requested, List<String> available, String label) {
        String metric = clean(requested);
        if (metric == null) return available.isEmpty() ? null : available.get(0);
        metric = METRIC_ALIASES.getOrDefault(metric, metric);
        if (!available.contains(metric)) {
            throw new IllegalArgumentException(label + "“" + metric + "”不可用；可选值："
                    + String.join("、", available));
        }
        return metric;
    }

    private static String selectSortMetric(String requested, String quantityMetric) {
        String metric = clean(requested);
        if (metric == null) return "revenue";
        if (!SORT_METRICS.contains(metric)) {
            throw new IllegalArgumentException("排序指标“" + metric
                    + "”不可用；可选值：revenue、quantity、unitPrice、profit");
        }
        if (("quantity".equals(metric) || "unitPrice".equals(metric)) && quantityMetric == null) {
            throw new IllegalArgumentException("当前报表没有可用于“" + metric + "”排序的数量口径");
        }
        return metric;
    }

    private static Map<String, List<String>> normalizeFilters(Map<String, List<String>> raw,
                                                               List<String> dimensions,
                                                               Map<String, String> labels) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        raw.forEach((field, values) -> {
            String key = resolveDimension(clean(field), dimensions, labels, "筛选字段");
            if (values == null || values.isEmpty()) return;
            List<String> selected = values.stream().map(ExploreService::clean)
                    .filter(value -> value != null).distinct().limit(200).toList();
            if (!selected.isEmpty()) result.put(key, selected);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String resolveDimension(String requested, List<String> available,
                                           Map<String, String> labels, String fieldLabel) {
        if (requested != null && available.contains(requested)) return requested;
        if (requested != null) {
            for (String dimension : available) {
                if (requested.equals(labels.get(dimension)) || requested.equals(STANDARD_LABELS.get(dimension))) {
                    return dimension;
                }
            }
        }
        throw new IllegalArgumentException(fieldLabel + "“" + requested + "”不可用；可选值："
                + String.join("、", available));
    }

    private static Map<String, List<String>> filterOptions(List<DataRow> rows, List<String> dimensions) {
        Map<String, TreeSet<String>> values = new LinkedHashMap<>();
        dimensions.forEach(dimension -> values.put(dimension, new TreeSet<>()));
        for (DataRow row : rows) {
            for (String dimension : dimensions) {
                TreeSet<String> options = values.get(dimension);
                if (options.size() >= MAX_FILTER_VALUES) continue;
                options.add(label(value(row, dimension)));
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    private static Map<String, List<String>> withDisplayAliases(Map<String, List<String>> options,
                                                                 Map<String, String> labels) {
        Map<String, List<String>> result = new LinkedHashMap<>(options);
        options.forEach((dimension, values) -> {
            String standard = STANDARD_LABELS.get(dimension);
            if (present(standard)) result.putIfAbsent(standard, values);
            String actual = labels.get(dimension);
            if (present(actual)) result.putIfAbsent(actual, values);
        });
        return result;
    }

    private static boolean matches(DataRow row, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            if (!filter.getValue().contains(label(value(row, filter.getKey())))) return false;
        }
        return true;
    }

    private static String value(DataRow row, String dimension) {
        return switch (dimension) {
            case "product" -> row.product();
            case "customer" -> row.customer();
            case "year" -> row.date() == null ? null : String.valueOf(row.date().getYear());
            case "month" -> row.date() == null ? null : YearMonth.from(row.date()).toString();
            default -> row.dimensions().get(dimension);
        };
    }

    private static List<String> availableYears(List<DataRow> rows) {
        return rows.stream().filter(row -> row.date() != null)
                .map(row -> String.valueOf(row.date().getYear())).distinct().sorted().toList();
    }

    private static List<String> availableMonths(List<DataRow> rows) {
        return rows.stream().filter(row -> row.date() != null)
                .map(row -> YearMonth.from(row.date()).toString()).distinct().sorted().toList();
    }

    private static PeriodSelection normalizePeriodSelection(ExploreRequest request,
                                                              List<String> availableYears,
                                                              List<String> availableMonths) {
        String requestedMode = clean(request.periodMode());
        String mode = requestedMode == null ? "cumulative" : requestedMode.toLowerCase(Locale.ROOT);
        mode = switch (mode) {
            case "cumulative", "累计" -> "cumulative";
            case "year", "annual", "年度", "按年" -> "year";
            case "month", "monthly", "月度", "按月" -> "month";
            default -> throw new IllegalArgumentException(
                    "periodMode“" + requestedMode + "”不可用；可选值：cumulative、year、month");
        };
        // The mode controls presentation/grain. Explicit year/month selections are independent
        // scope predicates and must never be discarded merely because the user chose cumulative.
        List<String> years = normalizeYears(request.years(), availableYears);
        List<String> months = normalizeMonths(request.months(), availableMonths);
        return new PeriodSelection(mode, years, months);
    }

    private static List<String> normalizeYears(List<String> values, List<String> available) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            String value = clean(raw);
            if (value == null || !value.matches("(?:19|20)\\d{2}")) {
                throw new IllegalArgumentException("年度“" + raw + "”格式无效，应为 YYYY");
            }
            if (!available.contains(value)) {
                throw new IllegalArgumentException("年度“" + value + "”不在当前数据范围内");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeMonths(List<String> values, List<String> available) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            String value = clean(raw);
            try {
                value = value == null ? null : YearMonth.parse(value).toString();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("月度“" + raw + "”格式无效，应为 YYYY-MM");
            }
            if (value == null || !available.contains(value)) {
                throw new IllegalArgumentException("月度“" + raw + "”不在当前数据范围内");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private record PeriodSelection(String mode, List<String> years, List<String> months) {
        private boolean unrestricted() {
            return years.isEmpty() && months.isEmpty();
        }

        private boolean matches(DataRow row) {
            if (years.isEmpty() && months.isEmpty()) return true;
            if (row.date() == null) return false;
            if (!years.isEmpty() && !years.contains(String.valueOf(row.date().getYear()))) return false;
            return months.isEmpty() || months.contains(YearMonth.from(row.date()).toString());
        }
    }

    private record SessionMetadata(List<String> availableYears, List<String> availableMonths,
                                   Map<String, String> labels, List<String> dimensions,
                                   Map<String, String> drillSuggestions) {
    }

    private static final int DRILL_MAX_GROUPS = 2000;
    private static final long DRILL_MIN_PAIRS = 30;
    private static final double DRILL_MIN_COVERAGE = 0.5;
    private static final double DRILL_MIN_PURITY = 0.85;
    private static final double DRILL_MIN_FANOUT = 1.3;
    private static final double DRILL_GROUP_RATIO = 1.2;

    /**
     * Discovers parent → child drill paths from the data itself instead of hardcoding them per
     * workbook.  A dimension A drills into B when: B is strictly finer (more groups), almost every
     * B value belongs to exactly one A value (purity), and A values fan out to several B values —
     * so drilling actually reveals structure.  Works for any schema: org hierarchies
     * (部门 → 销售组), logistic paths (送货客户 → 送货地址), or whatever the workbook contains.
     */
    private static Map<String, String> drillSuggestions(List<DataRow> rows, List<String> dimensions) {
        if (rows.isEmpty() || dimensions.size() < 2) return Map.of();
        Map<String, Set<String>> distinct = new LinkedHashMap<>();
        dimensions.forEach(dimension -> distinct.put(dimension, new HashSet<>()));
        for (DataRow row : rows) {
            for (String dimension : dimensions) {
                String value = value(row, dimension);
                if (present(value)) distinct.get(dimension).add(value.strip());
            }
        }
        List<String> candidates = dimensions.stream()
                .filter(dimension -> {
                    int size = distinct.get(dimension).size();
                    return size >= 2 && size <= DRILL_MAX_GROUPS;
                })
                .toList();
        Map<String, String> suggestions = new LinkedHashMap<>();
        for (String parent : candidates) {
            int parentGroups = distinct.get(parent).size();
            String bestChild = null;
            int bestGroups = Integer.MAX_VALUE;
            double bestPurity = 0;
            for (String child : candidates) {
                if (child.equals(parent)) continue;
                int childGroups = distinct.get(child).size();
                if (childGroups < Math.max(2, parentGroups * DRILL_GROUP_RATIO)) continue;
                DrillPairStats stats = drillPairStats(rows, parent, child);
                if (stats.pairs() < DRILL_MIN_PAIRS
                        || stats.pairs() < rows.size() * DRILL_MIN_COVERAGE
                        || stats.purity() < DRILL_MIN_PURITY
                        || stats.fanout() < DRILL_MIN_FANOUT) continue;
                // 取满足条件的"最近一层"（组数最少）作为直接子级，而不是一步跳到最细维度。
                if (childGroups < bestGroups || (childGroups == bestGroups && stats.purity() > bestPurity)) {
                    bestGroups = childGroups;
                    bestPurity = stats.purity();
                    bestChild = child;
                }
            }
            if (bestChild != null) suggestions.put(parent, bestChild);
        }
        return Collections.unmodifiableMap(suggestions);
    }

    private static DrillPairStats drillPairStats(List<DataRow> rows, String parent, String child) {
        Map<String, Set<String>> childrenPerParent = new HashMap<>();
        Map<String, Set<String>> parentsPerChild = new HashMap<>();
        long pairs = 0;
        for (DataRow row : rows) {
            String parentValue = value(row, parent);
            String childValue = value(row, child);
            if (!present(parentValue) || !present(childValue)) continue;
            pairs++;
            childrenPerParent.computeIfAbsent(parentValue.strip(), ignored -> new HashSet<>()).add(childValue.strip());
            parentsPerChild.computeIfAbsent(childValue.strip(), ignored -> new HashSet<>()).add(parentValue.strip());
        }
        long pureChildren = parentsPerChild.values().stream().filter(values -> values.size() == 1).count();
        double purity = parentsPerChild.isEmpty() ? 0 : (double) pureChildren / parentsPerChild.size();
        double fanout = childrenPerParent.values().stream().mapToInt(Set::size).average().orElse(0);
        return new DrillPairStats(pairs, purity, fanout);
    }

    private record DrillPairStats(long pairs, double purity, double fanout) {
    }

    private static boolean fuzzyMatches(String value, String search) {
        if (search == null) return true;
        String normalizedSearch = normalizeForFuzzySearch(search);
        return normalizedSearch.isEmpty()
                || normalizeForFuzzySearch(label(value)).contains(normalizedSearch);
    }

    /**
     * Produces a comparison-only form without changing the original group label returned to callers.
     * NFKC folds full-width Latin/digits and compatibility spaces; filtering both Java whitespace and
     * Unicode space characters also covers non-breaking and ideographic spaces.
     */
    private static String normalizeForFuzzySearch(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint)
                        && !Character.isSpaceChar(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .toLowerCase(Locale.ROOT);
    }

    private static String selectProfitFilter(String requested) {
        String filter = clean(requested);
        if (filter == null) return null;
        if ("亏损".equals(filter)) return "loss";
        if (!"loss".equals(filter)) {
            throw new IllegalArgumentException("profitFilter“" + filter + "”不可用；可选值：loss");
        }
        return filter;
    }

    private static boolean matchesProfitFilter(ExploreResponse.ExploreItem item, String profitFilter) {
        if (profitFilter == null) return true;
        return "loss".equals(profitFilter) && item.profitAvailable()
                && item.profit() != null && item.profit().signum() < 0;
    }

    private static Comparator<ExploreResponse.ExploreItem> itemComparator(String sortBy) {
        if ("profit".equals(sortBy)) {
            return Comparator.comparing(ExploreResponse.ExploreItem::profitAvailable).reversed()
                    .thenComparing(ExploreResponse.ExploreItem::profit,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ExploreResponse.ExploreItem::revenue,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ExploreResponse.ExploreItem::records, Comparator.reverseOrder())
                    .thenComparing(ExploreResponse.ExploreItem::name);
        }
        Comparator<ExploreResponse.ExploreItem> selected = Comparator.comparing(
                item -> sortValue(item, sortBy), Comparator.nullsLast(Comparator.reverseOrder()));
        if (!"revenue".equals(sortBy)) {
            selected = selected.thenComparing(
                    ExploreResponse.ExploreItem::revenue,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return selected
                .thenComparing(ExploreResponse.ExploreItem::records, Comparator.reverseOrder())
                .thenComparing(ExploreResponse.ExploreItem::name);
    }

    private static BigDecimal sortValue(ExploreResponse.ExploreItem item, String sortBy) {
        return switch (sortBy) {
            case "quantity" -> item.quantity();
            case "unitPrice" -> item.unitPrice();
            case "profit" -> item.profit();
            default -> item.revenue();
        };
    }

    private static String label(String value) {
        return present(value) ? value.strip() : "未填写";
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static final class Aggregate {
        private long records;
        private final Set<String> products = new HashSet<>();
        private final Set<String> customers = new HashSet<>();
        private final Set<String> salesGroups = new HashSet<>();
        private final Map<String, Long> specCounts = new HashMap<>();
        private long revenueRows;
        private long comparableRows;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal absoluteRevenue = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal comparableRevenue = BigDecimal.ZERO;
        private BigDecimal comparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal comparableCost = BigDecimal.ZERO;
        private BigDecimal operatingComparableRevenue = BigDecimal.ZERO;
        private BigDecimal operatingComparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal operatingComparableCost = BigDecimal.ZERO;
        private BigDecimal operatingComparableExpense = BigDecimal.ZERO;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal unitComparableRevenue = BigDecimal.ZERO;
        private BigDecimal unitComparableQuantity = BigDecimal.ZERO;
        private BigDecimal averageCostComparableCost = BigDecimal.ZERO;
        private BigDecimal averageCostComparableQuantity = BigDecimal.ZERO;
        private BigDecimal unitProfitComparableRevenue = BigDecimal.ZERO;
        private BigDecimal unitProfitComparableCost = BigDecimal.ZERO;
        private BigDecimal unitProfitComparableQuantity = BigDecimal.ZERO;
        private BigDecimal salesQuantity = BigDecimal.ZERO;
        private BigDecimal outboundQuantity = BigDecimal.ZERO;
        private BigDecimal convertedQuantity = BigDecimal.ZERO;
        private BigDecimal transferCost = BigDecimal.ZERO;
        private BigDecimal transferComparableRevenue = BigDecimal.ZERO;
        private BigDecimal transferComparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal transferComparableCost = BigDecimal.ZERO;
        private BigDecimal confirmedCost = BigDecimal.ZERO;
        private BigDecimal confirmedComparableRevenue = BigDecimal.ZERO;
        private BigDecimal confirmedComparableAbsoluteRevenue = BigDecimal.ZERO;
        private BigDecimal confirmedComparableCost = BigDecimal.ZERO;
        private boolean revenueAvailable;
        private boolean costAvailable;
        private boolean expenseAvailable;
        private boolean quantityAvailable;
        private boolean salesQuantityAvailable;
        private boolean outboundQuantityAvailable;
        private boolean convertedQuantityAvailable;
        private boolean transferCostAvailable;
        private boolean confirmedCostAvailable;
        private long transferComparableRows;
        private long confirmedComparableRows;
        private long operatingComparableRows;

        private void add(DataRow row, String quantityMetric, String costMetric) {
            records++;
            if (present(row.product())) products.add(row.product());
            if (present(row.customer())) customers.add(row.customer());
            if (present(row.dimensions().get("salesGroup"))) {
                salesGroups.add(row.dimensions().get("salesGroup").strip());
            }
            String spec = row.dimensions().get("productSpec");
            if (present(spec)) specCounts.merge(spec.strip(), 1L, Long::sum);
            boolean validRevenue = row.metricValid("revenue");
            boolean validCost = costMetric != null && row.metricValid(costMetric);
            boolean validConfirmedCost = row.metricValid("cost");
            boolean validExpense = row.metricValid("expense");
            boolean validQuantity = quantityMetric != null && row.metricValid(quantityMetric);
            boolean validSalesQuantity = row.metricValid("quantity");
            boolean validOutboundQuantity = row.metricValid("outboundQuantity");
            boolean validConvertedQuantity = row.metricValid("convertedQuantity");
            boolean validTransferCost = row.metricValid("transferCost");
            if (validRevenue) {
                revenueRows++;
                revenueAvailable = true;
                revenue = revenue.add(row.revenue());
                absoluteRevenue = absoluteRevenue.add(row.revenue().abs());
            }
            if (validCost) {
                costAvailable = true;
                cost = cost.add(row.measure(costMetric));
            }
            if (validExpense) {
                expenseAvailable = true;
                expense = expense.add(row.expense());
            }
            if (validQuantity) {
                quantityAvailable = true;
                quantity = quantity.add(row.measure(quantityMetric));
            }
            if (validSalesQuantity) {
                salesQuantityAvailable = true;
                salesQuantity = salesQuantity.add(row.quantity());
            }
            if (validOutboundQuantity) {
                outboundQuantityAvailable = true;
                outboundQuantity = outboundQuantity.add(row.measure("outboundQuantity"));
            }
            if (validConvertedQuantity) {
                convertedQuantityAvailable = true;
                convertedQuantity = convertedQuantity.add(row.measure("convertedQuantity"));
            }
            if (validTransferCost) {
                transferCostAvailable = true;
                transferCost = transferCost.add(row.measure("transferCost"));
            }
            if (validConfirmedCost) {
                confirmedCostAvailable = true;
                confirmedCost = confirmedCost.add(row.measure("cost"));
            }
            if (validRevenue && validCost) {
                comparableRows++;
                comparableRevenue = comparableRevenue.add(row.revenue());
                comparableAbsoluteRevenue = comparableAbsoluteRevenue.add(row.revenue().abs());
                comparableCost = comparableCost.add(row.measure(costMetric));
            }
            if (validRevenue && validCost && validExpense) {
                operatingComparableRows++;
                operatingComparableRevenue = operatingComparableRevenue.add(row.revenue());
                operatingComparableAbsoluteRevenue = operatingComparableAbsoluteRevenue.add(row.revenue().abs());
                operatingComparableCost = operatingComparableCost.add(row.measure(costMetric));
                operatingComparableExpense = operatingComparableExpense.add(row.expense());
            }
            if (validRevenue && validQuantity) {
                unitComparableRevenue = unitComparableRevenue.add(row.revenue());
                unitComparableQuantity = unitComparableQuantity.add(row.measure(quantityMetric));
            }
            if (validCost && validQuantity) {
                averageCostComparableCost = averageCostComparableCost.add(row.measure(costMetric));
                averageCostComparableQuantity = averageCostComparableQuantity.add(row.measure(quantityMetric));
            }
            if (validRevenue && validCost && validQuantity) {
                unitProfitComparableRevenue = unitProfitComparableRevenue.add(row.revenue());
                unitProfitComparableCost = unitProfitComparableCost.add(row.measure(costMetric));
                unitProfitComparableQuantity = unitProfitComparableQuantity.add(row.measure(quantityMetric));
            }
            if (validRevenue && validTransferCost) {
                transferComparableRows++;
                transferComparableRevenue = transferComparableRevenue.add(row.revenue());
                transferComparableAbsoluteRevenue = transferComparableAbsoluteRevenue.add(row.revenue().abs());
                transferComparableCost = transferComparableCost.add(row.measure("transferCost"));
            }
            if (validRevenue && validConfirmedCost) {
                confirmedComparableRows++;
                confirmedComparableRevenue = confirmedComparableRevenue.add(row.revenue());
                confirmedComparableAbsoluteRevenue = confirmedComparableAbsoluteRevenue.add(row.revenue().abs());
                confirmedComparableCost = confirmedComparableCost.add(row.measure("cost"));
            }
        }

        private ExploreResponse.ExploreItem item(String key, String name, BigDecimal shareDenominator) {
            BigDecimal coverage = decimal(comparableAbsoluteRevenue, absoluteRevenue);
            // "Comparable" already means that only rows where revenue and the chosen cost are
            // both valid participate.  A low coverage ratio is a quality warning, not a reason to
            // erase the computable result.  Keeping the value and its coverage side by side lets
            // users review partially covered departments instead of seeing an unexplained blank.
            boolean profitAvailable = revenueAvailable && costAvailable && comparableRows > 0;
            BigDecimal profit = profitAvailable ? comparableRevenue.subtract(comparableCost) : null;
            BigDecimal margin = profitAvailable && comparableRevenue.signum() > 0
                    ? decimal(profit, comparableRevenue) : null;
            BigDecimal operatingCoverage = decimal(operatingComparableAbsoluteRevenue, absoluteRevenue);
            boolean operatingProfitAvailable = revenueAvailable && costAvailable && expenseAvailable
                    && operatingComparableRows > 0;
            BigDecimal operatingProfit = operatingProfitAvailable
                    ? operatingComparableRevenue.subtract(operatingComparableCost)
                    .subtract(operatingComparableExpense) : null;
            BigDecimal operatingMargin = operatingProfitAvailable && operatingComparableRevenue.signum() > 0
                    ? decimal(operatingProfit, operatingComparableRevenue) : null;
            BigDecimal unitPrice = quantityAvailable ? decimal(unitComparableRevenue, unitComparableQuantity) : null;
            BigDecimal averageCost = quantityAvailable
                    ? decimal(averageCostComparableCost, averageCostComparableQuantity) : null;
            BigDecimal unitGrossProfit = quantityAvailable
                    ? decimal(unitProfitComparableRevenue.subtract(unitProfitComparableCost),
                    unitProfitComparableQuantity) : null;
            BigDecimal share = revenueAvailable ? decimal(revenue, shareDenominator) : null;
            BigDecimal transferCoverage = decimal(transferComparableAbsoluteRevenue, absoluteRevenue);
            boolean transferProfitAvailable = revenueAvailable && transferCostAvailable
                    && transferComparableRows > 0;
            BigDecimal transferGrossProfit = transferProfitAvailable
                    ? transferComparableRevenue.subtract(transferComparableCost) : null;
            BigDecimal transferGrossMargin = transferProfitAvailable && transferComparableRevenue.signum() > 0
                    ? decimal(transferGrossProfit, transferComparableRevenue) : null;
            BigDecimal confirmedCoverage = decimal(confirmedComparableAbsoluteRevenue, absoluteRevenue);
            boolean confirmedProfitAvailable = revenueAvailable && confirmedCostAvailable
                    && confirmedComparableRows > 0;
            BigDecimal confirmedGrossProfit = confirmedProfitAvailable
                    ? confirmedComparableRevenue.subtract(confirmedComparableCost) : null;
            BigDecimal confirmedGrossMargin = confirmedProfitAvailable
                    && confirmedComparableRevenue.signum() > 0
                    ? decimal(confirmedGrossProfit, confirmedComparableRevenue) : null;
            return new ExploreResponse.ExploreItem(key, name, records, products.size(), customers.size(),
                    salesGroups.stream().sorted().toList(),
                    revenueAvailable ? money(revenue) : null, costAvailable ? money(cost) : null,
                    expenseAvailable ? money(expense) : null,
                    profitAvailable ? money(comparableRevenue) : null,
                    profitAvailable ? money(comparableCost) : null,
                    money(profit), margin, coverage,
                    operatingProfitAvailable ? money(operatingComparableRevenue) : null,
                    operatingProfitAvailable ? money(operatingComparableCost) : null,
                    operatingProfitAvailable ? money(operatingComparableExpense) : null,
                    money(operatingProfit), operatingMargin, operatingCoverage, operatingProfitAvailable,
                    quantityAvailable ? quantity.setScale(4, RoundingMode.HALF_UP) : null,
                    unitPrice == null ? null : unitPrice.setScale(4, RoundingMode.HALF_UP), share,
                    comparableRows, Math.max(0, revenueRows - comparableRows), profitAvailable,
                    quantityAvailable,
                    salesQuantityAvailable ? salesQuantity.setScale(4, RoundingMode.HALF_UP) : null,
                    outboundQuantityAvailable ? outboundQuantity.setScale(4, RoundingMode.HALF_UP) : null,
                    convertedQuantityAvailable ? convertedQuantity.setScale(4, RoundingMode.HALF_UP) : null,
                    averageCost == null ? null : averageCost.setScale(4, RoundingMode.HALF_UP),
                    unitGrossProfit == null ? null : unitGrossProfit.setScale(4, RoundingMode.HALF_UP),
                    transferCostAvailable ? money(transferCost) : null,
                    transferProfitAvailable ? money(transferComparableRevenue) : null,
                    transferProfitAvailable ? money(transferComparableCost) : null,
                    money(transferGrossProfit), transferGrossMargin, transferCoverage,
                    outboundQuantityAvailable, convertedQuantityAvailable, transferProfitAvailable,
                    confirmedCostAvailable ? money(confirmedCost) : null,
                    confirmedProfitAvailable ? money(confirmedComparableRevenue) : null,
                    confirmedProfitAvailable ? money(confirmedComparableCost) : null,
                    money(confirmedGrossProfit), confirmedGrossMargin, confirmedCoverage,
                    confirmedProfitAvailable, modalSpec(), topSpecs());
        }

        /** Representative spec for the group: the most frequent 规格型号 among its rows. */
        private String modalSpec() {
            List<String> specs = topSpecs();
            return specs.isEmpty() ? null : specs.get(0);
        }

        /**
         * Top specs by row count, most frequent first (up to 3).  Products sold under several
         * 规格型号 keep every variant visible instead of silently collapsing into one guess.
         */
        private List<String> topSpecs() {
            return specCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }
}
