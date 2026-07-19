package com.datamaster.app.service;

import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.QualityReport;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExportServiceTest {
    @Test
    void excelExportsUseDifferentAnnualMonthlyAndCumulativeComparisonGrains() throws Exception {
        var session = session();
        ExportService exporter = new ExportService(new ExploreService());

        byte[] annualBytes = exporter.excel(session, "year");
        byte[] monthlyBytes = exporter.excel(session, "month");
        byte[] cumulativeBytes = exporter.excel(session, "cumulative");

        try (XSSFWorkbook annual = new XSSFWorkbook(new ByteArrayInputStream(annualBytes));
             XSSFWorkbook monthly = new XSSFWorkbook(new ByteArrayInputStream(monthlyBytes));
             XSSFWorkbook cumulative = new XSSFWorkbook(new ByteArrayInputStream(cumulativeBytes))) {
            assertThat(annual.getSheetName(4)).isEqualTo("年度比较");
            assertThat(annual.getSheet("年度比较").getRow(0).getCell(0).getStringCellValue()).isEqualTo("年度");
            assertThat(columnValues(annual, "年度比较")).containsExactly("2025", "2026");

            assertThat(monthly.getSheetName(4)).isEqualTo("月度比较");
            assertThat(monthly.getSheet("月度比较").getRow(0).getCell(0).getStringCellValue()).isEqualTo("月份");
            assertThat(columnValues(monthly, "月度比较"))
                    .containsExactly("2025-12", "2026-01", "2026-02");

            assertThat(cumulative.getSheetName(4)).isEqualTo("月度趋势-累计范围");
            assertThat(cumulative.getSheet("经营总览").getRow(1).getCell(0).getStringCellValue())
                    .contains("累计分析（含月度趋势）");
        }
        assertThat(annualBytes).isNotEqualTo(monthlyBytes).isNotEqualTo(cumulativeBytes);
    }

    @Test
    void wordExportsStateAndRenderTheRequestedComparisonGrain() throws Exception {
        var session = session();
        ExportService exporter = new ExportService(new ExploreService());

        String annual = wordText(exporter.word(session, "year"));
        String monthly = wordText(exporter.word(session, "month"));
        String cumulative = wordText(exporter.word(session, "cumulative"));

        assertThat(annual).contains("期间口径：年度对比", "二、年度对比", "2025", "2026")
                .doesNotContain("2025-12");
        assertThat(monthly).contains("期间口径：月度对比", "二、月度对比",
                "2025-12", "2026-01", "2026-02");
        assertThat(cumulative).contains("期间口径：累计分析（含月度趋势）", "二、月度对比",
                "2025-12", "2026-01", "2026-02");
    }

    @Test
    void excelProductAnalysisUsesTheDesktopQuantityPriceProfitContract() throws Exception {
        ExportService exporter = new ExportService(new ExploreService());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                exporter.excel(session(), "month")))) {
            var sheet = workbook.getSheet("产品分析");
            assertThat(java.util.stream.IntStream.range(0, 11)
                    .mapToObj(index -> sheet.getRow(0).getCell(index).getStringCellValue()).toList())
                    .containsExactly("产品", "销售数量（源单位）", "换算只数（只）", "平均单价（元/只）",
                            "平均成本（元/只）", "单只毛利（元/只）", "销售额", "可比成本", "毛利润",
                            "毛利率", "毛利可算覆盖率");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(80.0);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(40.0);
        }
    }

    @Test
    void wordExportContainsStylesPartAndDocumentLevelEastAsianFontDefaults() throws Exception {
        byte[] bytes = new ExportService(new ExploreService()).word(session(), "month");
        Set<String> entries = new HashSet<>();
        String stylesXml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if ("word/styles.xml".equals(entry.getName())) {
                    stylesXml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(entries).contains("word/styles.xml");
        assertThat(stylesXml).contains("w:docDefaults", "w:rFonts", "w:eastAsia=");
    }

    private static List<String> columnValues(XSSFWorkbook workbook, String sheetName) {
        var sheet = workbook.getSheet(sheetName);
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            if (sheet.getRow(index) != null) values.add(sheet.getRow(index).getCell(0).getStringCellValue());
        }
        return values;
    }

    private static String wordText(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder result = new StringBuilder();
            document.getParagraphs().forEach(value -> result.append(value.getText()).append('\n'));
            document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
                    .forEach(cell -> result.append(cell.getText()).append('\t'))));
            return result.toString();
        }
    }

    private static com.datamaster.app.domain.AnalysisSession session() {
        List<DataRow> rows = List.of(
                row(LocalDate.of(2025, 12, 1), "产品甲", "400", "240", "40", "20"),
                row(LocalDate.of(2026, 1, 1), "产品乙", "600", "300", "60", "30"),
                row(LocalDate.of(2026, 2, 1), "产品丙", "800", "480", "80", "40")
        );
        AnalysisService analysis = new AnalysisService();
        var result = analysis.analyze(new SpreadsheetImportService.ImportResult(rows, 1,
                new QualityReport(rows.size(), rows.size(), 0, 0, 0, 0, List.of())));
        return analysis.requireSession(result.id());
    }

    private static DataRow row(LocalDate date, String product, String revenue, String cost,
                               String outbound, String converted) {
        return new DataRow("source.xlsx", date, product, "客户", bd(outbound), bd(revenue), bd(cost),
                BigDecimal.ZERO, Map.of(),
                Set.of("revenue", "cost", "quantity", "outboundQuantity", "convertedQuantity"),
                Map.of("outboundQuantity", bd(outbound), "convertedQuantity", bd(converted)));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
