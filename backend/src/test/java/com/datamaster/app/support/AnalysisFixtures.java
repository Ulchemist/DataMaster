package com.datamaster.app.support;

import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.QualityReport;
import com.datamaster.app.service.AnalysisService;
import com.datamaster.app.service.SpreadsheetImportService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared deterministic business-analysis fixture available only to tests. */
public final class AnalysisFixtures {
    private AnalysisFixtures() {
    }

    public static AnalysisResult completeAnalysis(AnalysisService analysis) {
        List<DataRow> rows = rows();
        QualityReport quality = new QualityReport(rows.size(), rows.size(), 0, 0, 0, 0, List.of());
        return analysis.analyze(new SpreadsheetImportService.ImportResult(rows, 2, quality));
    }

    private static List<DataRow> rows() {
        return List.of(
                row("2026-01-03", "标准版", "华东商贸", 80, 64_000, 43_000, 12_000),
                row("2026-01-11", "专业版", "远航零售", 35, 49_000, 31_000, 10_000),
                row("2026-01-19", "入门版", "新城渠道", 140, 42_000, 45_000, 9_000),
                row("2026-02-05", "标准版", "华东商贸", 70, 56_000, 41_000, 12_500),
                row("2026-02-14", "专业版", "远航零售", 30, 42_000, 29_000, 9_500),
                row("2026-02-22", "入门版", "新城渠道", 150, 45_000, 50_000, 9_500),
                row("2026-03-04", "标准版", "华东商贸", 60, 48_000, 38_000, 13_000),
                row("2026-03-13", "专业版", "远航零售", 26, 36_400, 26_500, 10_000),
                row("2026-03-24", "入门版", "新城渠道", 160, 48_000, 55_000, 10_500)
        );
    }

    private static DataRow row(String date, String product, String customer, int quantity,
                               int revenue, int cost, int expense) {
        String source = date.startsWith("2026-03") ? "测试经营数据_3月.csv" : "测试经营数据_1-2月.xlsx";
        Map<String, String> dimensions = dimensions(product);
        Map<String, BigDecimal> measures = Map.of(
                "outboundQuantity", BigDecimal.valueOf(quantity),
                "convertedQuantity", BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(12)),
                "transferCost", BigDecimal.valueOf(Math.max(0, cost - 1_000))
        );
        return new DataRow(source, LocalDate.parse(date), product, customer,
                BigDecimal.valueOf(quantity), BigDecimal.valueOf(revenue),
                BigDecimal.valueOf(cost), BigDecimal.valueOf(expense), dimensions,
                Set.of("quantity", "outboundQuantity", "convertedQuantity", "revenue", "cost",
                        "transferCost", "expense"), measures);
    }

    private static Map<String, String> dimensions(String product) {
        if ("标准版".equals(product)) {
            return Map.ofEntries(
                    Map.entry("customerAnalysisCategory", "核心客户"),
                    Map.entry("customerAnalysisLargeCategory", "直销客户"),
                    Map.entry("businessAnalysisCategory", "成熟产品"),
                    Map.entry("businessAnalysisLargeCategory", "标准方案"),
                    Map.entry("productForm", "标准交付"),
                    Map.entry("region", "华东"), Map.entry("department", "销售一部"),
                    Map.entry("salesGroup", "华东A组"), Map.entry("channel", "直销"));
        }
        if ("专业版".equals(product)) {
            return Map.ofEntries(
                    Map.entry("customerAnalysisCategory", "成长客户"),
                    Map.entry("customerAnalysisLargeCategory", "渠道客户"),
                    Map.entry("businessAnalysisCategory", "增长产品"),
                    Map.entry("businessAnalysisLargeCategory", "专业方案"),
                    Map.entry("productForm", "顾问交付"),
                    Map.entry("region", "华南"), Map.entry("department", "销售二部"),
                    Map.entry("salesGroup", "华南B组"), Map.entry("channel", "经销"));
        }
        return Map.ofEntries(
                Map.entry("customerAnalysisCategory", "新客"),
                Map.entry("customerAnalysisLargeCategory", "线上客户"),
                Map.entry("businessAnalysisCategory", "引流产品"),
                Map.entry("businessAnalysisLargeCategory", "入门方案"),
                Map.entry("productForm", "自助交付"),
                Map.entry("region", "华北"), Map.entry("department", "增长业务部"),
                Map.entry("salesGroup", "线上增长组"), Map.entry("channel", "线上"));
    }
}
