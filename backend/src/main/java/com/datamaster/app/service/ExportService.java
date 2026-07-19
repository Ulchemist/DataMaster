package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.AnalysisCapabilities;
import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.MonthlyBreakdown;
import com.datamaster.app.domain.QualityReport;
import com.datamaster.app.domain.ExploreRequest;
import com.datamaster.app.domain.ExploreResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ExportService {
    // Generate with a native CJK family on each target OS so Word/WPS and preview exporters do not
    // depend on unreliable cross-platform fallback. Windows receives Microsoft YaHei; macOS
    // receives Arial Unicode MS, whose TrueType outline is handled consistently by Word, WPS and
    // the headless LibreOffice renderer used by our export QA. The document remains editable when
    // opened on the other platform.
    private static final String FONT_NAME = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac") ? "Arial Unicode MS" : "Microsoft YaHei";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ExploreService explorer;

    public ExportService(ExploreService explorer) {
        this.explorer = explorer;
    }

    public byte[] excel(AnalysisSession session) {
        return excel(session, "cumulative");
    }

    public byte[] excel(AnalysisSession session, String periodMode) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ExcelStyles styles = new ExcelStyles(workbook);
            String mode = normalizePeriodMode(periodMode);
            overviewSheet(workbook, session, styles, mode);
            dataSheet(workbook, session.rows(), styles);
            productDetailSheet(workbook, session, styles);
            breakdownSheet(workbook, "客户分析", session.result().customers(), session.result().capabilities(), styles);
            comparisonSheet(workbook, session, mode, styles);
            qualitySheet(workbook, session.result(), styles);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 Excel 报告失败", ex);
        }
    }

    public byte[] word(AnalysisSession session) {
        return word(session, "cumulative");
    }

    public byte[] word(AnalysisSession session, String periodMode) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configureWordStyles(document);
            String mode = normalizePeriodMode(periodMode);
            title(document, "DataMaster 经营分析报告");
            paragraph(document, "生成时间：" + LocalDateTime.now().format(TIMESTAMP), false);
            paragraph(document, "数据范围：" + session.result().sourceFileCount() + " 个文件，"
                    + session.result().rowCount() + " 条明细", false);
            paragraph(document, "期间口径：" + periodModeLabel(mode), false);

            heading(document, "一、经营结果总览", 1);
            FinancialSummary summary = session.result().summary();
            AnalysisCapabilities capabilities = session.result().capabilities();
            XWPFTable summaryTable = document.createTable(1, 2);
            setTableHeader(summaryTable.getRow(0), List.of("指标", "结果"));
            if (capabilities.hasRevenue()) addWordRow(summaryTable, "营业收入", money(summary.revenue()));
            if (capabilities.hasCost()) addWordRow(summaryTable, "已识别成本", money(summary.cost()));
            if (capabilities.grossProfitAvailable()) {
                addWordRow(summaryTable, "可比口径毛利", money(summary.grossProfit()));
                addWordRow(summaryTable, "可比口径毛利率", percent(summary.grossMargin()));
                addWordRow(summaryTable, "毛利可算收入覆盖率", percent(capabilities.grossProfitRevenueCoverage()));
            }
            if (capabilities.hasExpenses()) addWordRow(summaryTable, "已识别期间费用", money(summary.expenses()));
            if (capabilities.operatingProfitAvailable()) {
                addWordRow(summaryTable, "可比口径经营利润", money(summary.operatingProfit()));
                addWordRow(summaryTable, "可比口径经营利润率", percent(summary.operatingMargin()));
            }
            if (!capabilities.grossProfitAvailable()) addWordRow(summaryTable, "利润分析", "不可用：成本口径未确认或可比覆盖不足");
            else if (!capabilities.operatingProfitAvailable()) addWordRow(summaryTable, "经营利润", "不可用：缺少可靠期间费用口径");

            heading(document, "二、" + ("year".equals(mode) ? "年度对比" : "月度对比"), 1);
            comparisonWordTable(document, session, mode);

            heading(document, "三、重点发现与改进建议", 1);
            if (session.result().insights().isEmpty()) {
                paragraph(document, "当前没有生成改进建议。", false);
            } else {
                int index = 1;
                for (Insight insight : session.result().insights()) {
                    paragraph(document, index++ + ". " + insight.title(), true);
                    paragraph(document, "发现：" + insight.finding(), false);
                    paragraph(document, "证据：" + insight.evidence(), false);
                    paragraph(document, "建议：" + insight.action(), false);
                }
            }

            heading(document, "四、产品分析", 1);
            breakdownWordTable(document, session.result().products(), capabilities, 15);
            heading(document, "五、客户分析", 1);
            breakdownWordTable(document, session.result().customers(), capabilities, 15);

            heading(document, "六、数据质量说明", 1);
            QualityReport quality = session.result().quality();
            paragraph(document, "有效明细：" + quality.validRows() + "/" + quality.totalRows()
                    + "；无法解析的数值：" + quality.invalidNumericCells() + " 个。", false);
            if (quality.warnings().isEmpty()) {
                paragraph(document, "未发现需要提示的数据质量问题。", false);
            } else {
                quality.warnings().forEach(value -> paragraph(document, "• " + value, false));
            }

            heading(document, "口径说明", 1);
            paragraph(document, "利润仅使用收入与相关成本、费用同时有效的可比行计算；公式错误和缺失数值不会按 0 补入。"
                    + "本报告中的金额由程序确定性计算，AI 仅用于字段建议、解释和行动建议。", false);
            capabilities.unavailableReasons().forEach(value -> paragraph(document, "• " + value, false));
            document.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 Word 报告失败", ex);
        }
    }

    private static void overviewSheet(Workbook workbook, AnalysisSession session, ExcelStyles styles,
                                      String periodMode) {
        Sheet sheet = workbook.createSheet("经营总览");
        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("DataMaster 经营分析结果");
        titleCell.setCellStyle(styles.title);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
        Row meta = sheet.createRow(1);
        meta.createCell(0).setCellValue("数据文件数：" + session.result().sourceFileCount()
                + "；明细行数：" + session.result().rowCount()
                + "；期间口径：" + periodModeLabel(periodMode));

        Row header = sheet.createRow(3);
        writeHeader(header, styles, "指标", "结果", "口径/备注");
        FinancialSummary s = session.result().summary();
        AnalysisCapabilities capabilities = session.result().capabilities();
        int row = 4;
        if (capabilities.hasRevenue()) row = metricRow(sheet, row, styles, "营业收入", s.revenue(), false, "全部有效收入合计");
        if (capabilities.hasCost()) row = metricRow(sheet, row, styles, "已识别成本", s.cost(), false, "全部有效成本值合计");
        if (capabilities.grossProfitAvailable()) {
            row = metricRow(sheet, row, styles, "可比口径毛利", s.grossProfit(), false,
                    "仅收入与成本同时有效的行；排除 " + capabilities.grossProfitExcludedRows() + " 行");
            row = metricRow(sheet, row, styles, "可比口径毛利率", s.grossMargin(), true,
                    "毛利可算收入覆盖率 " + percent(capabilities.grossProfitRevenueCoverage()));
        } else {
            row = textMetricRow(sheet, row, styles, "毛利与毛利率", "不可用", "成本口径未确认或毛利可算收入覆盖不足");
        }
        if (capabilities.hasExpenses()) row = metricRow(sheet, row, styles, "已识别期间费用", s.expenses(), false, "全部有效期间费用合计");
        if (capabilities.operatingProfitAvailable()) {
            row = metricRow(sheet, row, styles, "可比口径经营利润", s.operatingProfit(), false,
                    "仅收入、成本和费用同时有效的行");
            row = metricRow(sheet, row, styles, "可比口径经营利润率", s.operatingMargin(), true,
                    "经营利润可算收入覆盖率 " + percent(capabilities.operatingProfitRevenueCoverage()));
        } else {
            row = textMetricRow(sheet, row, styles, "经营利润与利润率", "不可用", "未识别可靠期间费用或可比覆盖不足");
        }

        int insightStart = row + 3;
        Row insightHeader = sheet.createRow(insightStart);
        writeHeader(insightHeader, styles, "改进建议", "数据发现", "行动与证据");
        int current = insightStart + 1;
        for (Insight insight : session.result().insights()) {
            Row item = sheet.createRow(current++);
            writeText(item.createCell(0), insight.title(), styles.wrap);
            writeText(item.createCell(1), insight.finding(), styles.wrap);
            writeText(item.createCell(2), insight.action() + "\n证据：" + insight.evidence(), styles.wrap);
            item.setHeightInPoints(48);
        }
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 28 * 256);
        sheet.setColumnWidth(2, 55 * 256);
        sheet.createFreezePane(0, 4);
    }

    private static void dataSheet(Workbook workbook, List<DataRow> rows, ExcelStyles styles) {
        Sheet sheet = workbook.createSheet("合并数据");
        Row header = sheet.createRow(0);
        writeHeader(header, styles, "来源文件", "日期", "产品", "客户", "销售数量（源单位）",
                "换算只数（只）", "收入", "成本", "费用");
        int index = 1;
        for (DataRow value : rows) {
            Row row = sheet.createRow(index++);
            writeText(row.createCell(0), value.sourceFile(), styles.text);
            writeText(row.createCell(1), value.date() == null ? "" : value.date().toString(), styles.text);
            writeText(row.createCell(2), value.product(), styles.text);
            writeText(row.createCell(3), value.customer(), styles.text);
            writeMetric(row.createCell(4), value.quantity(), value.metricValid("quantity"), styles.number);
            writeMetric(row.createCell(5), value.measure("convertedQuantity"),
                    value.metricValid("convertedQuantity"), styles.number);
            writeMetric(row.createCell(6), value.revenue(), value.metricValid("revenue"), styles.money);
            writeMetric(row.createCell(7), value.cost(), value.metricValid("cost"), styles.money);
            writeMetric(row.createCell(8), value.expense(), value.metricValid("expense"), styles.money);
        }
        widths(sheet, 22, 13, 20, 22, 18, 16, 16, 16, 16);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, 8));
        sheet.createFreezePane(0, 1);
    }

    /**
     * Exports the same quantity/price/profit contract used by the desktop product-detail page.
     * The explorer is paged so a workbook is never silently limited to the first 500 products.
     */
    private void productDetailSheet(Workbook workbook, AnalysisSession session, ExcelStyles styles) {
        Sheet sheet = workbook.createSheet("产品分析");
        List<ExploreResponse.ExploreItem> products = new java.util.ArrayList<>();
        ExploreResponse page = null;
        int offset = 0;
        do {
            page = explorer.explore(session.result().id(), session,
                    new ExploreRequest("product", Map.of(), null, null,
                            "revenue", null, null, offset, 500,
                            "cumulative", List.of(), List.of()));
            products.addAll(page.items());
            offset += page.returnedGroups();
        } while (page.hasMore() && page.returnedGroups() > 0);

        boolean perConvertedUnit = page != null && "convertedQuantity".equals(page.quantityMetric());
        String priceUnit = perConvertedUnit ? "元/只" : "元/源单位";
        Row header = sheet.createRow(0);
        writeHeader(header, styles, "产品", "销售数量（源单位）", "换算只数（只）",
                "平均单价（" + priceUnit + "）", "平均成本（" + priceUnit + "）",
                "单只毛利（" + priceUnit + "）", "销售额", "可比成本", "毛利润", "毛利率", "毛利可算覆盖率");
        int index = 1;
        for (ExploreResponse.ExploreItem value : products) {
            Row row = sheet.createRow(index++);
            writeText(row.createCell(0), value.name(), styles.text);
            writeMetric(row.createCell(1), value.salesQuantity(), value.salesQuantity() != null, styles.number);
            writeMetric(row.createCell(2), value.convertedQuantity(), value.convertedQuantityAvailable(), styles.number);
            writeMetric(row.createCell(3), value.unitPrice(), value.unitPrice() != null, styles.money);
            writeMetric(row.createCell(4), value.averageCost(), value.averageCost() != null, styles.money);
            writeMetric(row.createCell(5), value.unitGrossProfit(), value.unitGrossProfit() != null, styles.money);
            writeMetric(row.createCell(6), value.revenue(), value.revenue() != null, styles.money);
            writeMetric(row.createCell(7), value.comparableCost(), value.profitAvailable(), styles.money);
            writeMetric(row.createCell(8), value.profit(), value.profitAvailable(), styles.money);
            writeMetric(row.createCell(9), value.margin(), value.profitAvailable(), styles.percent);
            writeMetric(row.createCell(10), value.profitCoverage(), value.profitCoverage() != null, styles.percent);
        }
        widths(sheet, 28, 18, 16, 18, 18, 18, 18, 18, 18, 13, 18);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, products.size()), 0, 10));
        sheet.createFreezePane(1, 1);
    }

    private static void breakdownSheet(Workbook workbook, String name, List<Breakdown> values,
                                       AnalysisCapabilities capabilities, ExcelStyles styles) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        writeHeader(header, styles, name.startsWith("产品") ? "产品" : "客户", "数量", "收入", "成本",
                "可比毛利", "费用", "可比经营利润", "毛利率", "经营利润率");
        int index = 1;
        for (Breakdown value : values) {
            Row row = sheet.createRow(index++);
            writeText(row.createCell(0), value.name(), styles.text);
            writeNumber(row.createCell(1), value.quantity(), styles.number);
            writeNumber(row.createCell(2), value.revenue(), styles.money);
            writeMetric(row.createCell(3), value.cost(), capabilities.hasCost(), styles.money);
            writeMetric(row.createCell(4), value.grossProfit(), value.grossProfitAvailable(), styles.money);
            writeMetric(row.createCell(5), value.expenses(), capabilities.hasExpenses(), styles.money);
            writeMetric(row.createCell(6), value.operatingProfit(), value.operatingProfitAvailable(), styles.money);
            writeMetric(row.createCell(7), value.grossMargin(), value.grossProfitAvailable(), styles.percent);
            writeMetric(row.createCell(8), value.operatingMargin(), value.operatingProfitAvailable(), styles.percent);
        }
        widths(sheet, 24, 12, 16, 16, 16, 16, 16, 13, 15);
        sheet.createFreezePane(1, 1);
    }

    private void comparisonSheet(Workbook workbook, AnalysisSession session,
                                 String periodMode, ExcelStyles styles) {
        boolean annual = "year".equals(periodMode);
        Sheet sheet = workbook.createSheet(annual ? "年度比较" : "month".equals(periodMode)
                ? "月度比较" : "月度趋势-累计范围");
        Row header = sheet.createRow(0);
        writeHeader(header, styles, annual ? "年度" : "月份", "换算只数", "收入", "成本",
                "毛利", "费用", "经营利润", "毛利率", "经营利润率");
        ExploreResponse comparison = comparison(session, periodMode);
        int index = 1;
        for (ExploreResponse.ExploreItem value : comparison == null ? List.<ExploreResponse.ExploreItem>of()
                : comparison.items().stream().sorted(java.util.Comparator.comparing(
                ExploreResponse.ExploreItem::name)).toList()) {
            Row row = sheet.createRow(index++);
            writeText(row.createCell(0), value.name(), styles.text);
            writeMetric(row.createCell(1), value.convertedQuantity(), value.convertedQuantityAvailable(), styles.number);
            writeNumber(row.createCell(2), value.revenue(), styles.money);
            writeMetric(row.createCell(3), value.cost(), value.cost() != null, styles.money);
            writeMetric(row.createCell(4), value.profit(), value.profitAvailable(), styles.money);
            writeMetric(row.createCell(5), value.expense(), value.expense() != null, styles.money);
            writeMetric(row.createCell(6), value.operatingProfit(), value.operatingProfitAvailable(), styles.money);
            writeMetric(row.createCell(7), value.margin(), value.profitAvailable(), styles.percent);
            writeMetric(row.createCell(8), value.operatingMargin(), value.operatingProfitAvailable(), styles.percent);
        }
        widths(sheet, 14, 12, 16, 16, 16, 16, 16, 13, 15);
        sheet.createFreezePane(1, 1);
    }

    private ExploreResponse comparison(AnalysisSession session, String periodMode) {
        String groupBy = "year".equals(periodMode) ? "year" : "month";
        try {
            return explorer.explore(session.result().id(), session,
                    new ExploreRequest(groupBy, Map.of(), null, null,
                            null, null, null, 0, 500, periodMode, List.of(), List.of()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void comparisonWordTable(XWPFDocument document, AnalysisSession session, String periodMode) {
        ExploreResponse comparison = comparison(session, periodMode);
        if (comparison == null || comparison.items().isEmpty()) {
            paragraph(document, "当前报表没有可用于期间比较的日期字段。", false);
            return;
        }
        boolean annual = "year".equals(periodMode);
        XWPFTable table = document.createTable(1, 6);
        setTableHeader(table.getRow(0), List.of(annual ? "年度" : "月份", "换算只数", "收入",
                "成本", "毛利润", "毛利率"));
        comparison.items().stream().sorted(java.util.Comparator.comparing(ExploreResponse.ExploreItem::name))
                .forEach(value -> {
                    XWPFTableRow row = table.createRow();
                    setWordCell(row.getCell(0), value.name(), false);
                    setWordCell(row.getCell(1), number(value.convertedQuantity()), false);
                    setWordCell(row.getCell(2), money(value.revenue()), false);
                    setWordCell(row.getCell(3), money(value.cost()), false);
                    setWordCell(row.getCell(4), value.profitAvailable() ? money(value.profit()) : "不可用", false);
                    setWordCell(row.getCell(5), value.profitAvailable() ? percent(value.margin()) : "不可用", false);
                });
    }

    private static void qualitySheet(Workbook workbook, AnalysisResult result, ExcelStyles styles) {
        QualityReport value = result.quality();
        Sheet sheet = workbook.createSheet("数据质量");
        Row header = sheet.createRow(0);
        writeHeader(header, styles, "检查项", "结果", "说明");
        int row = 1;
        row = textMetricRow(sheet, row, styles, "数据总行数", String.valueOf(value.totalRows()), "导入并参与分析的明细");
        row = textMetricRow(sheet, row, styles, "有效行数", String.valueOf(value.validRows()), "已读取并进入能力判断的明细");
        row = textMetricRow(sheet, row, styles, "缺少日期", String.valueOf(value.missingDate()), "归入未指定日期");
        row = textMetricRow(sheet, row, styles, "缺少产品", String.valueOf(value.missingProduct()), "归入未指定产品");
        row = textMetricRow(sheet, row, styles, "缺少客户", String.valueOf(value.missingCustomer()), "归入未指定客户");
        row = textMetricRow(sheet, row, styles, "无效数值单元格", String.valueOf(value.invalidNumericCells()), "不会按 0 计入相关利润；改用可比行或停用指标");
        row = textMetricRow(sheet, row, styles, "毛利可算收入覆盖率", percent(result.capabilities().grossProfitRevenueCoverage()),
                "排除 " + result.capabilities().grossProfitExcludedRows() + " 行，涉及收入 "
                        + money(result.capabilities().grossProfitExcludedRevenue()));
        row++;
        Row warningHeader = sheet.createRow(row++);
        writeHeader(warningHeader, styles, "质量提示", "", "");
        for (String warning : value.warnings()) {
            Row warningRow = sheet.createRow(row++);
            writeText(warningRow.createCell(0), warning, styles.wrap);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(warningRow.getRowNum(), warningRow.getRowNum(), 0, 2));
        }
        for (String reason : result.capabilities().unavailableReasons()) {
            Row warningRow = sheet.createRow(row++);
            writeText(warningRow.createCell(0), "能力限制：" + reason, styles.wrap);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(warningRow.getRowNum(), warningRow.getRowNum(), 0, 2));
        }
        widths(sheet, 30, 18, 55);
    }

    private static int metricRow(Sheet sheet, int index, ExcelStyles styles, String label,
                                 BigDecimal value, boolean isPercent, String note) {
        Row row = sheet.createRow(index);
        writeText(row.createCell(0), label, styles.text);
        writeNumber(row.createCell(1), value, isPercent ? styles.percent : styles.money);
        writeText(row.createCell(2), note, styles.text);
        return index + 1;
    }

    private static int textMetricRow(Sheet sheet, int index, ExcelStyles styles, String label,
                                     String value, String note) {
        Row row = sheet.createRow(index);
        writeText(row.createCell(0), label, styles.text);
        writeText(row.createCell(1), value, styles.text);
        writeText(row.createCell(2), note, styles.text);
        return index + 1;
    }

    private static void writeHeader(Row row, ExcelStyles styles, String... headers) {
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(styles.header);
        }
    }

    private static void writeText(Cell cell, String value, CellStyle style) {
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void writeNumber(Cell cell, BigDecimal value, CellStyle style) {
        cell.setCellValue((value == null ? BigDecimal.ZERO : value).doubleValue());
        cell.setCellStyle(style);
    }

    private static void writeMetric(Cell cell, BigDecimal value, boolean available, CellStyle style) {
        if (available && value != null) cell.setCellValue(value.doubleValue());
        else cell.setBlank();
        cell.setCellStyle(style);
    }

    private static void widths(Sheet sheet, int... characters) {
        for (int index = 0; index < characters.length; index++) {
            sheet.setColumnWidth(index, Math.min(255, characters[index]) * 256);
        }
    }

    private static void title(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_NAME);
        run.setLang("zh-CN");
        run.setFontSize(20);
    }

    /** Creates a real styles part with a CJK-capable document default for Word/WPS/LibreOffice. */
    private static void configureWordStyles(XWPFDocument document) {
        XWPFStyles styles = document.createStyles();
        CTFonts fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(FONT_NAME);
        fonts.setHAnsi(FONT_NAME);
        fonts.setEastAsia(FONT_NAME);
        fonts.setCs(FONT_NAME);
        styles.setDefaultFonts(fonts);
    }

    private static void heading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_NAME);
        run.setLang("zh-CN");
        run.setFontSize(level == 1 ? 14 : 12);
    }

    private static void paragraph(XWPFDocument document, String text, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(90);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontFamily(FONT_NAME);
        run.setLang("zh-CN");
        run.setFontSize(10);
    }

    private static void breakdownWordTable(XWPFDocument document, List<Breakdown> values,
                                           AnalysisCapabilities capabilities, int limit) {
        XWPFTable table = document.createTable(1, 5);
        setTableHeader(table.getRow(0), List.of("名称", "收入", "可比毛利", "可比经营利润", "经营利润率"));
        values.stream().limit(limit).forEach(value -> {
            XWPFTableRow row = table.createRow();
            setWordCell(row.getCell(0), value.name(), false);
            setWordCell(row.getCell(1), money(value.revenue()), false);
            setWordCell(row.getCell(2), capabilities.grossProfitAvailable() && value.grossProfitAvailable()
                    ? money(value.grossProfit()) : "不可用", false);
            setWordCell(row.getCell(3), capabilities.operatingProfitAvailable() && value.operatingProfitAvailable()
                    ? money(value.operatingProfit()) : "不可用", false);
            setWordCell(row.getCell(4), capabilities.operatingProfitAvailable() && value.operatingProfitAvailable()
                    ? percent(value.operatingMargin()) : "不可用", false);
        });
    }

    private static void setTableHeader(XWPFTableRow row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            setWordCell(row.getCell(index), values.get(index), true);
        }
    }

    private static void addWordRow(XWPFTable table, String name, String value) {
        XWPFTableRow row = table.createRow();
        setWordCell(row.getCell(0), name, false);
        setWordCell(row.getCell(1), value, false);
    }

    private static void setWordCell(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph paragraph = cell.addParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontFamily(FONT_NAME);
        run.setLang("zh-CN");
        run.setFontSize(9);
    }

    private static String money(BigDecimal value) {
        if (value == null) return "-";
        return "¥" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        if (value == null) return "-";
        return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static String number(BigDecimal value) {
        if (value == null) return "-";
        return value.stripTrailingZeros().toPlainString();
    }

    private static String normalizePeriodMode(String value) {
        String mode = value == null || value.isBlank() ? "cumulative"
                : value.strip().toLowerCase(Locale.ROOT);
        if (!List.of("cumulative", "year", "month").contains(mode)) {
            throw new IllegalArgumentException("periodMode“" + value
                    + "”不可用；可选值：cumulative、year、month");
        }
        return mode;
    }

    private static String periodModeLabel(String mode) {
        return switch (mode) {
            case "year" -> "年度对比";
            case "month" -> "月度对比";
            default -> "累计分析（含月度趋势）";
        };
    }

    private static final class ExcelStyles {
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle wrap;
        private final CellStyle money;
        private final CellStyle number;
        private final CellStyle percent;

        private ExcelStyles(Workbook workbook) {
            Font normalFont = workbook.createFont();
            normalFont.setFontName(FONT_NAME);
            normalFont.setFontHeightInPoints((short) 10);
            Font titleFont = workbook.createFont();
            titleFont.setFontName(FONT_NAME);
            titleFont.setFontHeightInPoints((short) 18);
            titleFont.setBold(true);
            Font headerFont = workbook.createFont();
            headerFont.setFontName(FONT_NAME);
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            border(header);

            text = workbook.createCellStyle();
            text.setFont(normalFont);
            text.setVerticalAlignment(VerticalAlignment.CENTER);
            border(text);

            wrap = workbook.createCellStyle();
            wrap.cloneStyleFrom(text);
            wrap.setWrapText(true);

            money = workbook.createCellStyle();
            money.cloneStyleFrom(text);
            money.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00;[Red]-¥#,##0.00"));

            number = workbook.createCellStyle();
            number.cloneStyleFrom(text);
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));

            percent = workbook.createCellStyle();
            percent.cloneStyleFrom(text);
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.0%;[Red]-0.0%"));
        }

        private static void border(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
