package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisCapabilities;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.DynamicBreakdown;
import com.datamaster.app.domain.ExploreRequest;
import com.datamaster.app.domain.ExploreResponse;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.MonthlyBreakdown;
import com.datamaster.app.domain.PdfReportRequest;
import com.datamaster.app.domain.QualityReport;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Creates a selectable, vector PDF report without sending data to a third-party service. */
@Service
public class PdfReportService {
    private static final Color INK = new Color(31, 42, 68);
    private static final Color MUTED = new Color(104, 113, 139);
    private static final Color PRIMARY = new Color(91, 91, 214);
    private static final Color PRIMARY_SOFT = new Color(236, 236, 253);
    private static final Color CYAN = new Color(53, 167, 232);
    private static final Color ORANGE = new Color(242, 159, 103);
    private static final Color RED = new Color(201, 94, 85);
    private static final Color GREEN = new Color(45, 145, 112);
    private static final Color BORDER = new Color(220, 224, 237);
    private static final Color SURFACE = new Color(247, 248, 252);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String FONT_PROPERTY = "datamaster.pdf.font";
    private static final String FONT_ENV = "DATAMASTER_PDF_FONT";
    private static final int[] REQUIRED_CJK_GLYPHS = "经营分析数据客户产品销售组¥"
            .codePoints().toArray();

    private final BaseFont base;
    private final String fontSource;
    private final boolean embeddedUnicodeFont;
    private final Font title;
    private final Font subtitle;
    private final Font h1;
    private final Font h2;
    private final Font body;
    private final Font bodyMuted;
    private final Font small;
    private final Font dense;
    private final Font smallWhite;
    private final Font metric;
    private final ExploreService explorer;
    private final AnalysisService analysis;

    PdfReportService() {
        this(new ExploreService(), new AnalysisService());
    }

    @Autowired
    public PdfReportService(ExploreService explorer, AnalysisService analysis) {
        this.explorer = explorer;
        this.analysis = analysis;
        FontSelection selection = resolveChineseFont();
        this.base = selection.base();
        this.fontSource = selection.source();
        this.embeddedUnicodeFont = selection.embeddedUnicode();
        this.title = font(26, Font.BOLD, INK);
        this.subtitle = font(11, Font.NORMAL, MUTED);
        this.h1 = font(17, Font.BOLD, INK);
        this.h2 = font(12, Font.BOLD, INK);
        this.body = font(9.5f, Font.NORMAL, INK);
        this.bodyMuted = font(9, Font.NORMAL, MUTED);
        this.small = font(8, Font.NORMAL, MUTED);
        this.dense = font(8.2f, Font.NORMAL, INK);
        this.smallWhite = font(8, Font.BOLD, Color.WHITE);
        this.metric = font(17, Font.BOLD, INK);
    }

    /** Package-visible diagnostics used by the PDF portability test. */
    boolean usesEmbeddedUnicodeFont() {
        return embeddedUnicodeFont;
    }

    String selectedFontSource() {
        return fontSource;
    }

    public byte[] create(AnalysisSession session, PdfReportRequest request) {
        // All sections, including cover metadata, quality notes and insight evidence, must share
        // one period-scoped result. This also replaces full-workspace AI insights with freshly
        // calculated local insights for an explicit partial-period export.
        session = analysis.scopedSession(session, request.periodMode(), request.years(), request.months());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 42, 42, 48, 48);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new PageChrome(base));
            document.addTitle(request.title());
            document.addAuthor("DataMaster");
            document.addSubject("本地确定性经营分析报告");
            document.open();

            addCover(document, session, request);
            boolean firstSection = true;
            if (request.includes("overview")) {
                firstSection = sectionPage(document, firstSection);
                addOverview(document, session, request);
            }
            if (request.includes("trend")) {
                firstSection = sectionPage(document, firstSection);
                addTrend(document, session, request);
            }
            if (request.includes("product")) {
                firstSection = sectionPage(document, firstSection);
                addProductAnalysis(document, session, request.topN(), request);
            }
            if (request.includes("customer")) {
                firstSection = sectionPage(document, firstSection);
                addCustomerAnalysis(document, session, request.topN(), request);
            }
            if (request.includes("profit")) {
                firstSection = sectionPage(document, firstSection);
                addProfitAnalysis(document, session, request);
            }
            if (request.includes("organization")) {
                firstSection = sectionPage(document, firstSection);
                addOrganization(document, session, request.topN(), request);
            }
            if (request.includes("insights")) {
                firstSection = sectionPage(document, firstSection);
                addInsights(document, session);
            }
            if (request.includes("quality")) {
                firstSection = sectionPage(document, firstSection);
                addQuality(document, session);
            }

            document.close();
            return output.toByteArray();
        } catch (DocumentException | java.io.IOException ex) {
            throw new IllegalStateException("生成 PDF 报告失败", ex);
        }
    }

    private void addCover(Document document, AnalysisSession session, PdfReportRequest request)
            throws DocumentException {
        Paragraph eyebrow = new Paragraph("DATAMASTER / 经营分析报告", font(9, Font.BOLD, PRIMARY));
        eyebrow.setSpacingBefore(18);
        eyebrow.setSpacingAfter(16);
        document.add(eyebrow);

        Paragraph heading = new Paragraph(request.title(), title);
        heading.setLeading(34);
        document.add(heading);
        Paragraph description = new Paragraph("将经营结果、结构分析、利润风险与行动建议整理为可复核的交付材料。", subtitle);
        description.setSpacingBefore(8);
        description.setSpacingAfter(20);
        document.add(description);
        document.add(new LineSeparator(1.2f, 100, PRIMARY, Element.ALIGN_LEFT, 0));

        PdfPTable meta = new PdfPTable(new float[]{1, 1, 1});
        meta.setWidthPercentage(100);
        meta.setSpacingBefore(24);
        meta.addCell(metaCell("生成时间", LocalDateTime.now().format(TIMESTAMP)));
        meta.addCell(metaCell("数据范围", session.result().sourceFileCount() + " 个文件"));
        meta.addCell(metaCell("分析明细", session.result().rowCount() + " 行"));
        document.add(meta);

        ExploreResponse scoped = explore(session, null, Map.of(), null, "cost", 1, request);
        ExploreResponse.PeriodSummary summary = scoped == null ? null : scoped.periodSummary();
        PdfPTable kpis = new PdfPTable(new float[]{1, 1, 1});
        kpis.setWidthPercentage(100);
        kpis.setSpacingBefore(18);
        kpis.addCell(kpiCell("营业收入", summary != null && summary.revenue() != null
                ? money(summary.revenue()) : "不可用", PRIMARY));
        kpis.addCell(kpiCell("毛利率", summary != null && summary.grossProfitAvailable()
                ? percent(summary.grossMargin()) : "不可用", CYAN));
        kpis.addCell(kpiCell("经营利润", summary != null && summary.operatingProfitAvailable()
                ? money(summary.operatingProfit()) : "不可用",
                summary != null && summary.operatingProfitAvailable()
                        && summary.operatingProfit().signum() < 0 ? RED : GREEN));
        document.add(kpis);

        Paragraph note = new Paragraph("报告范围：" + request.sections().stream()
                .map(PdfReportService::sectionName).reduce((left, right) -> left + "、" + right).orElse("完整报告"), bodyMuted);
        note.setSpacingBefore(18);
        document.add(note);
        Paragraph period = new Paragraph("期间口径：" + periodLabel(request), small);
        period.setSpacingBefore(5);
        document.add(period);
        Paragraph rule = new Paragraph("金额与比率由本地程序计算；AI 只参与解释与行动建议，不改写底层数字。", small);
        rule.setSpacingBefore(6);
        document.add(rule);
    }

    private void addOverview(Document document, AnalysisSession session, PdfReportRequest request)
            throws DocumentException {
        sectionTitle(document, "01", "经营结果总览", "先看结果，再判断可用口径和主要风险");
        ExploreResponse scoped = explore(session, null, Map.of(), null, "cost", 1, request);
        ExploreResponse.PeriodSummary s = scoped == null ? null : scoped.periodSummary();
        AnalysisCapabilities c = session.result().capabilities();
        List<String[]> rows = new ArrayList<>();
        if (s != null && s.revenue() != null) rows.add(new String[]{"营业收入", money(s.revenue()), "当前期间有效收入合计"});
        if (s != null && s.cost() != null) rows.add(new String[]{"已识别成本", money(s.cost()), "当前期间已确认成本合计"});
        if (s != null && s.grossProfitAvailable()) {
            rows.add(new String[]{"可比口径毛利润", money(s.grossProfit()), "收入与成本同行有效"});
            rows.add(new String[]{"毛利率", percent(s.grossMargin()), "毛利可算覆盖率 " + percent(s.grossProfitCoverage())});
        }
        if (s != null && s.expense() != null) rows.add(new String[]{"期间费用", money(s.expense()), "当前期间已确认费用合计"});
        if (s != null && s.operatingProfitAvailable()) {
            rows.add(new String[]{"经营利润", money(s.operatingProfit()), "收入、成本与费用同行有效"});
            rows.add(new String[]{"经营利润率", percent(s.operatingMargin()), "经营利润可算覆盖率 " + percent(s.operatingProfitCoverage())});
        }
        if (rows.isEmpty()) rows.add(new String[]{"当前期间", "无可用数据", periodLabel(request)});
        document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), rows, new float[]{1.2f, 1, 2.4f}));

        if (!c.unavailableReasons().isEmpty()) {
            Paragraph warningTitle = new Paragraph("能力限制", h2);
            warningTitle.setSpacingBefore(16);
            warningTitle.setSpacingAfter(5);
            document.add(warningTitle);
            for (String reason : c.unavailableReasons()) addBullet(document, reason, RED);
        }
    }

    private void addTrend(Document document, AnalysisSession session, PdfReportRequest request)
            throws DocumentException {
        boolean annual = "year".equalsIgnoreCase(request.periodMode());
        String grain = annual ? "year" : "month";
        sectionTitle(document, "02", annual ? "年度趋势" : "月度趋势", "比较收入、成本和利润随时间的变化");
        ExploreResponse scoped = explore(session, grain, Map.of(), null, "cost", 500, request);
        List<ExploreResponse.ExploreItem> values = scoped == null ? List.of()
                : scoped.items().stream().sorted(Comparator.comparing(ExploreResponse.ExploreItem::name)).toList();
        if (values.isEmpty()) {
            addEmpty(document, "当前报表没有可用日期字段，无法生成月度趋势。");
            return;
        }
        BigDecimal max = values.stream().map(ExploreResponse.ExploreItem::revenue)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        PdfPTable chart = new PdfPTable(new float[]{1.1f, 3.4f, 1.4f, 1.3f});
        chart.setWidthPercentage(100);
        chart.setSpacingBefore(10);
        addHeader(chart, List.of(annual ? "年度" : "月份", "收入规模", "收入", "利润率"));
        for (ExploreResponse.ExploreItem item : values) {
            chart.addCell(textCell(item.name(), body, Element.ALIGN_LEFT));
            chart.addCell(barCell(ratio(item.revenue(), max), PRIMARY));
            chart.addCell(textCell(money(item.revenue()), body, Element.ALIGN_RIGHT));
            String margin = item.operatingProfitAvailable() ? marginText(item.operatingMargin())
                    : item.profitAvailable() ? marginText(item.margin()) : "不可用";
            chart.addCell(textCell(margin, body, Element.ALIGN_RIGHT));
        }
        document.add(chart);
    }

    private void addProductAnalysis(Document document, AnalysisSession session, int topN,
                                    PdfReportRequest request)
            throws DocumentException {
        sectionTitle(document, "03", "产品分析", "从经营分类、产品形态、销售额、数量、单价和利润质量拆解产品组合");
        ExploreResponse probe = explore(session, "product", Map.of(), null, null, topN, request);
        if (probe == null || !probe.availableDimensions().contains("product")) {
            addEmpty(document, "当前报表没有可用产品字段，无法生成产品结构与明细分析。");
            return;
        }
        String costMetric = probe.availableCostMetrics().contains("cost") ? "cost" : null;
        Map<String, String> structures = new LinkedHashMap<>();
        structures.put("businessAnalysisLargeCategory", "经营分析大类");
        structures.put("businessAnalysisCategory", "经营分析分类");
        structures.put("productForm", "产品形态（汇报）");
        for (Map.Entry<String, String> dimension : structures.entrySet()) {
            if (!probe.availableDimensions().contains(dimension.getKey())) continue;
            ExploreResponse result = explore(session, dimension.getKey(), Map.of(), null, costMetric, topN, request);
            if (result == null || result.items().isEmpty()) continue;
            addProductStructureLens(document, dimension.getValue(), result);
        }

        String quantityMetric = probe.availableQuantityMetrics().contains("convertedQuantity")
                ? "convertedQuantity" : probe.availableQuantityMetrics().contains("outboundQuantity")
                ? "outboundQuantity" : probe.availableQuantityMetrics().contains("quantity") ? "quantity" : null;
        ExploreResponse result = explore(session, "product", Map.of(), quantityMetric, costMetric, topN, request);
        if (result == null || result.items().isEmpty()) return;
        // Eleven auditable measures cannot remain legible in an A4 portrait table. Give this
        // detail ledger its own landscape page and allow normal row splitting across pages.
        document.setPageSize(PageSize.A4.rotate());
        document.newPage();
        PdfPTable table = new PdfPTable(new float[]{1.7f, .82f, .82f, .8f, .8f, .8f,
                1.05f, 1.05f, 1.05f, .68f, .68f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        table.setSplitRows(true);
        addHeader(table, List.of("产品", "销售数量", "换算只数", "平均单价", "平均成本", "单只毛利",
                "销售额", "可比成本", "毛利润", "毛利率", "可算覆盖"));
        for (ExploreResponse.ExploreItem item : result.items()) {
            BigDecimal salesQuantity = item.salesQuantity() != null
                    ? item.salesQuantity() : item.outboundQuantity();
            table.addCell(denseCell(item.name(), Element.ALIGN_LEFT));
            table.addCell(denseCell(number(salesQuantity), Element.ALIGN_RIGHT));
            table.addCell(denseCell(number(item.convertedQuantity()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(money(item.unitPrice()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(money(item.averageCost()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(money(item.unitGrossProfit()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(money(item.revenue()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(item.profitAvailable() ? money(item.comparableCost()) : "不可用",
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(item.profitAvailable() ? money(item.profit()) : "不可用",
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(profitMarginText(item), Element.ALIGN_RIGHT));
            table.addCell(denseCell(percent(item.profitCoverage()), Element.ALIGN_RIGHT));
        }
        addSubsection(document, "产品明细 · 量价利同屏",
                "销售数量保留源表单位；换算只数按只求和，平均单价、平均成本与单只毛利按换算只数口径计算。");
        document.add(table);
    }

    private void addCustomerAnalysis(Document document, AnalysisSession session, int topN,
                                     PdfReportRequest request)
            throws DocumentException {
        sectionTitle(document, "04", "客户分析", "从客户价值继续下钻产品利润，并按渠道查看前十名客户");
        ExploreResponse probe = explore(session, "customer", Map.of(), null, null, topN, request);
        if (probe == null || !probe.availableDimensions().contains("customer")) {
            addEmpty(document, "当前报表没有可用客户字段，无法生成客户价值与产品下钻。");
            return;
        }
        String costMetric = probe.availableCostMetrics().contains("cost") ? "cost" : null;
        ExploreResponse customers = explore(session, "customer", Map.of(), null, costMetric, topN, request);
        if (customers == null || customers.items().isEmpty()) {
            addEmpty(document, "当前筛选下没有客户记录。");
            return;
        }
        addSubsection(document, "客户价值明细", "利润按客户的收入与成本可比记录计算，不把缺失成本按零处理");
        PdfPTable customerTable = new PdfPTable(new float[]{2f, 1.25f, 1.25f, .75f, 1.1f, .85f, .75f});
        customerTable.setWidthPercentage(100);
        customerTable.setHeaderRows(1);
        addHeader(customerTable, List.of("客户", "可比收入", "可比成本", "产品数", "利润", "利润率", "覆盖"));
        for (ExploreResponse.ExploreItem item : customers.items()) {
            customerTable.addCell(denseCell(item.name(), Element.ALIGN_LEFT));
            customerTable.addCell(denseCell(item.profitAvailable() ? money(item.comparableRevenue()) : "不可用",
                    Element.ALIGN_RIGHT));
            customerTable.addCell(denseCell(item.profitAvailable() ? money(item.comparableCost()) : "不可用",
                    Element.ALIGN_RIGHT));
            customerTable.addCell(denseCell(String.valueOf(item.productCount()), Element.ALIGN_RIGHT));
            customerTable.addCell(denseCell(item.profitAvailable() ? money(item.profit()) : "不可用",
                    Element.ALIGN_RIGHT));
            customerTable.addCell(denseCell(profitMarginText(item),
                    Element.ALIGN_RIGHT));
            customerTable.addCell(denseCell(percent(item.profitCoverage()), Element.ALIGN_RIGHT));
        }
        document.add(customerTable);

        int customerDrillCount = Math.min(5, customers.items().size());
        for (ExploreResponse.ExploreItem customer : customers.items().subList(0, customerDrillCount)) {
            ExploreResponse products = explore(session, "product", Map.of("customer", List.of(customer.name())),
                    null, costMetric, Math.min(10, topN), request);
            if (products == null || products.items().isEmpty()) continue;
            addSubsectionTable(document, customer.name() + " · 产品利润",
                    "查看该客户内部各产品的收入与利润，而不是只看整体利润",
                    null, compactProfitTable("产品", products.items()));
        }

        if (probe.availableDimensions().contains("channel")) {
            ExploreResponse channels = explore(session, "channel", Map.of(), null, costMetric, 10, request);
            if (channels != null) {
                for (ExploreResponse.ExploreItem channel : channels.items().stream().limit(5).toList()) {
                    ExploreResponse topCustomers = explore(session, "customer",
                            Map.of("channel", List.of(channel.name())), null, costMetric, 10, request);
                    if (topCustomers == null || topCustomers.items().isEmpty()) continue;
                    addSubsectionTable(document, channel.name() + "渠道 · 前十名客户",
                            "渠道收入结构继续下钻到客户贡献与利润质量",
                            null, compactProfitTable("客户", topCustomers.items()));
                }
            }
        }
    }

    private void addProfitAnalysis(Document document, AnalysisSession session, PdfReportRequest request)
            throws DocumentException {
        sectionTitle(document, "05", "利润与成本", "区分全量字段合计与同行有效的可比口径，复核利润质量和盈亏平衡");
        ExploreResponse scoped = explore(session, null, Map.of(), null, "cost", 1, request);
        boolean explicitPeriodScope = !request.years().isEmpty() || !request.months().isEmpty()
                || (request.periodMode() != null && !"cumulative".equalsIgnoreCase(request.periodMode()));
        if (explicitPeriodScope && scoped != null && scoped.periodSummary() != null) {
            addScopedProfitAnalysis(document, scoped.periodSummary(), request);
            return;
        }
        FinancialSummary summary = session.result().summary();
        AnalysisCapabilities capabilities = session.result().capabilities();
        ProfitSnapshot snapshot = profitSnapshot(session.rows());
        BigDecimal grossComparableRevenue = comparableRevenue(summary.grossComparableRevenue(),
                summary.grossProfit(), summary.grossMargin(), snapshot.grossRevenue());
        BigDecimal grossComparableCost = firstAvailable(summary.grossComparableCost(),
                difference(grossComparableRevenue, summary.grossProfit(), snapshot.grossCost()));
        BigDecimal operatingComparableRevenue = comparableRevenue(summary.operatingComparableRevenue(),
                summary.operatingProfit(), summary.operatingMargin(), snapshot.operatingRevenue());
        BigDecimal operatingComparableCost = firstAvailable(summary.operatingComparableCost(),
                snapshot.operatingCost());
        BigDecimal operatingComparableExpenses = firstAvailable(summary.operatingComparableExpenses(),
                snapshot.operatingExpenses());

        PdfPTable kpis = new PdfPTable(new float[]{1, 1, 1});
        kpis.setWidthPercentage(100);
        kpis.setSpacingBefore(10);
        kpis.addCell(kpiCell("毛利可比收入", capabilities.grossProfitAvailable()
                ? money(grossComparableRevenue) : "不可用", PRIMARY));
        kpis.addCell(kpiCell("可比口径毛利润", capabilities.grossProfitAvailable()
                ? money(summary.grossProfit()) : "不可用",
                capabilities.grossProfitAvailable() && summary.grossProfit().signum() < 0 ? RED : CYAN));
        kpis.addCell(kpiCell("毛利率", capabilities.grossProfitAvailable()
                ? percent(summary.grossMargin()) : "不可用",
                capabilities.grossProfitAvailable() && summary.grossMargin().signum() < 0 ? RED : GREEN));
        document.add(kpis);

        addSubsection(document, "毛利可比口径", "毛利润只使用收入与成本同时有效的记录，不把缺失成本按零处理");
        List<String[]> grossRows = new ArrayList<>();
        if (capabilities.hasRevenue()) {
            grossRows.add(new String[]{"全量有效收入", money(summary.revenue()), "收入字段有效记录合计"});
        }
        if (capabilities.grossProfitAvailable()) {
            grossRows.add(new String[]{"可比收入", money(grossComparableRevenue),
                    snapshot.grossRows() + " 行收入与成本同行有效"});
            grossRows.add(new String[]{"可比成本", money(grossComparableCost), "仅汇总上述可比行成本"});
            grossRows.add(new String[]{"毛利润", money(summary.grossProfit()), "可比收入 - 可比成本"});
            grossRows.add(new String[]{"毛利率", percent(summary.grossMargin()), "毛利润 / 可比收入"});
        } else if (capabilities.hasCost()) {
            grossRows.add(new String[]{"已识别成本", money(summary.cost()), "毛利可比覆盖不足，暂不输出毛利润"});
        }
        grossRows.add(new String[]{"毛利可算收入覆盖率", percent(capabilities.grossProfitRevenueCoverage()),
                "收入与成本同时有效行绝对销售额 / 全部有效绝对销售额；排除 "
                        + capabilities.grossProfitExcludedRows() + " 行；100% 不代表毛利率 100%"});
        grossRows.add(new String[]{"排除收入", money(capabilities.grossProfitExcludedRevenue()),
                "未进入毛利计算的收入绝对额"});
        document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), grossRows,
                new float[]{1.25f, 1.2f, 2.6f}));

        if (!capabilities.grossProfitAvailable()) {
            addProfitLimitations(document, capabilities, "毛利润与毛利率不可用");
        }

        addSubsection(document, "费用、经营利润与盈亏平衡", "经营利润要求收入、成本和期间费用在同一记录上同时有效");
        if (capabilities.operatingProfitAvailable()) {
            BigDecimal expenseRate = divide(operatingComparableExpenses, operatingComparableRevenue);
            BigDecimal breakEven = summary.operatingProfit().abs();
            List<String[]> operatingRows = new ArrayList<>();
            operatingRows.add(new String[]{"经营可比收入", money(operatingComparableRevenue),
                    snapshot.operatingRows() + " 行收入、成本和费用同行有效"});
            operatingRows.add(new String[]{"经营可比成本", money(operatingComparableCost), "仅汇总经营可比行成本"});
            operatingRows.add(new String[]{"经营可比费用", money(operatingComparableExpenses),
                    "费用率 " + percent(expenseRate)});
            operatingRows.add(new String[]{"经营利润", money(summary.operatingProfit()),
                    "经营可比收入 - 成本 - 费用"});
            operatingRows.add(new String[]{"经营利润率", percent(summary.operatingMargin()),
                    "经营利润 / 经营可比收入"});
            operatingRows.add(new String[]{summary.operatingProfit().signum() < 0 ? "距盈亏平衡" : "盈亏平衡安全垫",
                    money(breakEven), summary.operatingProfit().signum() < 0
                    ? "至少释放同等成本费用或增加同等贡献利润"
                    : "利润下降至该金额后触及盈亏平衡"});
            operatingRows.add(new String[]{"经营利润可算收入覆盖率", percent(capabilities.operatingProfitRevenueCoverage()),
                    "排除 " + capabilities.operatingProfitExcludedRows() + " 行、收入 "
                            + money(capabilities.operatingProfitExcludedRevenue())});
            document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), operatingRows,
                    new float[]{1.25f, 1.2f, 2.6f}));
            String verdict = summary.operatingProfit().signum() < 0
                    ? "当前经营可比口径尚未达到盈亏平衡，亏损缺口为 " + money(breakEven) + "。"
                    : "当前经营可比口径已超过盈亏平衡，利润安全垫为 " + money(breakEven) + "。";
            addBullet(document, verdict, summary.operatingProfit().signum() < 0 ? RED : GREEN);
        } else {
            List<String[]> unavailableRows = new ArrayList<>();
            if (capabilities.hasExpenses()) {
                unavailableRows.add(new String[]{"已识别期间费用", money(summary.expenses()),
                        "费用字段已有数值，但经营可比覆盖不足"});
                unavailableRows.add(new String[]{"经营利润可算收入覆盖率",
                        percent(capabilities.operatingProfitRevenueCoverage()),
                        "排除 " + capabilities.operatingProfitExcludedRows() + " 行、收入 "
                                + money(capabilities.operatingProfitExcludedRevenue())});
                unavailableRows.add(new String[]{"经营利润与盈亏平衡", "不可用",
                        "不会使用不完整费用口径推导经营利润"});
            } else {
                unavailableRows.add(new String[]{"期间费用", "不可用", "源表未识别或未确认期间费用字段"});
                unavailableRows.add(new String[]{"经营利润与盈亏平衡", "不可用",
                        "补充并确认期间费用后才能计算"});
            }
            document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), unavailableRows,
                    new float[]{1.25f, 1.2f, 2.6f}));
            addProfitLimitations(document, capabilities, capabilities.hasExpenses()
                    ? "经营利润与盈亏平衡不可用" : "期间费用不可用，经营利润与盈亏平衡不可用");
        }
    }

    private void addScopedProfitAnalysis(Document document, ExploreResponse.PeriodSummary summary,
                                         PdfReportRequest request) throws DocumentException {
        PdfPTable kpis = new PdfPTable(new float[]{1, 1, 1});
        kpis.setWidthPercentage(100);
        kpis.setSpacingBefore(10);
        kpis.addCell(kpiCell("毛利可比收入", summary.grossProfitAvailable()
                ? money(summary.grossComparableRevenue()) : "不可用", PRIMARY));
        kpis.addCell(kpiCell("可比口径毛利润", summary.grossProfitAvailable()
                ? money(summary.grossProfit()) : "不可用",
                summary.grossProfitAvailable() && summary.grossProfit().signum() < 0 ? RED : CYAN));
        kpis.addCell(kpiCell("毛利率", summary.grossProfitAvailable()
                ? percent(summary.grossMargin()) : "不可用",
                summary.grossProfitAvailable() && summary.grossMargin().signum() < 0 ? RED : GREEN));
        document.add(kpis);

        addSubsection(document, "当前期间毛利口径", periodLabel(request)
                + "；毛利润只使用收入与成本同时有效的记录");
        List<String[]> grossRows = new ArrayList<>();
        grossRows.add(new String[]{"全量有效收入", money(summary.revenue()), "当前期间收入字段有效记录合计"});
        grossRows.add(new String[]{"已识别成本", money(summary.cost()), "当前期间成本字段有效记录合计"});
        if (summary.grossProfitAvailable()) {
            grossRows.add(new String[]{"可比收入", money(summary.grossComparableRevenue()), "收入与成本同行有效"});
            grossRows.add(new String[]{"可比成本", money(summary.grossComparableCost()), "仅汇总上述可比行成本"});
            grossRows.add(new String[]{"毛利润", money(summary.grossProfit()), "可比收入 - 可比成本"});
            grossRows.add(new String[]{"毛利率", percent(summary.grossMargin()), "毛利润 / 可比收入"});
        }
        grossRows.add(new String[]{"毛利可算收入覆盖率", percent(summary.grossProfitCoverage()),
                "收入与成本同时有效行绝对销售额 / 全部有效绝对销售额；低覆盖结果仅代表可比行，100% 不代表毛利率 100%"});
        document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), grossRows,
                new float[]{1.25f, 1.2f, 2.6f}));

        addSubsection(document, "费用、经营利润与盈亏平衡", "经营利润要求收入、成本和期间费用同行有效");
        List<String[]> operatingRows = new ArrayList<>();
        operatingRows.add(new String[]{"期间费用", money(summary.expense()), "当前期间已确认费用合计"});
        if (summary.operatingProfitAvailable()) {
            BigDecimal breakEven = summary.operatingProfit().abs();
            operatingRows.add(new String[]{"经营可比收入", money(summary.operatingComparableRevenue()),
                    "收入、成本和费用同行有效"});
            operatingRows.add(new String[]{"经营可比成本", money(summary.operatingComparableCost()),
                    "仅汇总经营可比行成本"});
            operatingRows.add(new String[]{"经营可比费用", money(summary.operatingComparableExpense()),
                    "仅汇总经营可比行费用"});
            operatingRows.add(new String[]{"经营利润", money(summary.operatingProfit()),
                    "经营可比收入 - 成本 - 费用"});
            operatingRows.add(new String[]{"经营利润率", percent(summary.operatingMargin()),
                    "经营利润 / 经营可比收入"});
            operatingRows.add(new String[]{summary.operatingProfit().signum() < 0 ? "距盈亏平衡" : "盈亏平衡安全垫",
                    money(breakEven), "按当前期间经营可比口径计算"});
            operatingRows.add(new String[]{"经营利润可算收入覆盖率", percent(summary.operatingProfitCoverage()),
                    "收入、成本、费用同时有效行的收入覆盖；低覆盖结果需复核"});
        } else {
            operatingRows.add(new String[]{"经营利润与盈亏平衡", "不可用",
                    "当前期间的收入、成本、费用可比覆盖不足"});
        }
        document.add(threeColumnTable(List.of("指标", "结果", "口径说明"), operatingRows,
                new float[]{1.25f, 1.2f, 2.6f}));
    }

    private void addProfitLimitations(Document document, AnalysisCapabilities capabilities, String fallback)
            throws DocumentException {
        List<String> reasons = capabilities.unavailableReasons().stream()
                .filter(value -> value.contains("成本") || value.contains("毛利")
                        || value.contains("费用") || value.contains("经营利润"))
                .toList();
        addBullet(document, fallback, RED);
        for (String reason : reasons) {
            if (!reason.equals(fallback)) addBullet(document, reason, RED);
        }
    }

    private static ProfitSnapshot profitSnapshot(List<DataRow> rows) {
        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal grossCost = BigDecimal.ZERO;
        BigDecimal operatingRevenue = BigDecimal.ZERO;
        BigDecimal operatingCost = BigDecimal.ZERO;
        BigDecimal operatingExpenses = BigDecimal.ZERO;
        long grossRows = 0;
        long operatingRows = 0;
        for (DataRow row : rows) {
            if (row.metricValid("revenue") && row.metricValid("cost")) {
                grossRows++;
                grossRevenue = grossRevenue.add(row.revenue());
                grossCost = grossCost.add(row.cost());
            }
            if (row.metricValid("revenue") && row.metricValid("cost") && row.metricValid("expense")) {
                operatingRows++;
                operatingRevenue = operatingRevenue.add(row.revenue());
                operatingCost = operatingCost.add(row.cost());
                operatingExpenses = operatingExpenses.add(row.expense());
            }
        }
        return new ProfitSnapshot(moneyValue(grossRevenue), moneyValue(grossCost), grossRows,
                moneyValue(operatingRevenue), moneyValue(operatingCost), moneyValue(operatingExpenses),
                operatingRows);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal comparableRevenue(BigDecimal explicit, BigDecimal profit,
                                                BigDecimal margin, BigDecimal rowFallback) {
        if (explicit != null) return explicit;
        if (profit != null && margin != null && margin.signum() != 0) {
            return profit.divide(margin, 2, RoundingMode.HALF_UP);
        }
        return rowFallback;
    }

    private static BigDecimal difference(BigDecimal revenue, BigDecimal profit, BigDecimal fallback) {
        if (revenue != null && profit != null) return revenue.subtract(profit);
        return fallback;
    }

    private static BigDecimal firstAvailable(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal moneyValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private void addBreakdownSection(Document document, String name, String description,
                                     List<Breakdown> values, AnalysisCapabilities capabilities,
                                     int topN) throws DocumentException {
        sectionTitle(document, "产品分析".equals(name) ? "03" : "04", name, description);
        if (values.isEmpty()) {
            addEmpty(document, "当前报表没有可用的" + (name.startsWith("产品") ? "产品" : "客户") + "字段。");
            return;
        }
        List<Breakdown> top = values.stream().limit(topN).toList();
        BigDecimal max = top.stream().map(Breakdown::revenue).max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        PdfPTable chart = new PdfPTable(new float[]{2.1f, 2.5f, 1.3f, 1.1f, 1.1f});
        chart.setWidthPercentage(100);
        chart.setSpacingBefore(10);
        addHeader(chart, List.of(name.startsWith("产品") ? "产品" : "客户", "收入规模", "收入", "数量", "利润率"));
        for (Breakdown item : top) {
            chart.addCell(textCell(item.name(), body, Element.ALIGN_LEFT));
            chart.addCell(barCell(ratio(item.revenue(), max), CYAN));
            chart.addCell(textCell(money(item.revenue()), body, Element.ALIGN_RIGHT));
            chart.addCell(textCell(number(item.quantity()), body, Element.ALIGN_RIGHT));
            String margin = item.operatingProfitAvailable() ? marginText(item.operatingMargin())
                    : item.grossProfitAvailable() ? marginText(item.grossMargin()) : "不可用";
            chart.addCell(textCell(margin, body, Element.ALIGN_RIGHT));
        }
        document.add(chart);
        Paragraph scope = new Paragraph("展示收入前 " + top.size() + " 项；完整明细可在 Excel 分析底稿中复核。", small);
        scope.setSpacingBefore(6);
        document.add(scope);
    }

    private void addOrganization(Document document, AnalysisSession session, int topN,
                                 PdfReportRequest request)
            throws DocumentException {
        sectionTitle(document, "06", "销售组织与阿米巴利润", "按调拨成本核算区域、部门和销售组利润，并继续下钻客户与产品");
        ExploreResponse probe = explore(session, null, Map.of(), null, null, topN, request);
        if (probe == null) {
            addEmpty(document, "当前报表没有识别到销售区域、销售部门或销售组字段。");
            return;
        }
        String costMetric = probe.availableCostMetrics().contains("transferCost")
                ? "transferCost" : probe.availableCostMetrics().contains("cost") ? "cost" : null;
        String costLabel = "transferCost".equals(costMetric) ? "调拨成本" : "成本";
        Map<String, String> organizations = new LinkedHashMap<>();
        organizations.put("region", "销售区域");
        organizations.put("department", "销售部门");
        organizations.put("salesGroup", "销售组");
        ExploreResponse salesGroups = null;
        for (Map.Entry<String, String> dimension : organizations.entrySet()) {
            if (!probe.availableDimensions().contains(dimension.getKey())) continue;
            ExploreResponse result = explore(session, dimension.getKey(), Map.of(), null, costMetric, topN, request);
            if (result == null || result.items().isEmpty()) continue;
            if ("salesGroup".equals(dimension.getKey())) salesGroups = result;
            addSubsection(document, dimension.getValue() + "利润", costMetric == null
                    ? "当前报表没有成本字段，仅展示销售额与组织规模"
                    : "transferCost".equals(costMetric) ? "阿米巴利润使用调拨成本口径" : "未识别调拨成本，暂用已确认成本口径");
            PdfPTable table = new PdfPTable(new float[]{1.7f, 1.15f, 1.15f, 1.05f, .8f, .7f, .65f, .65f});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            addHeader(table, List.of(dimension.getValue(), "可比收入", "可比" + costLabel, "利润", "利润率", "覆盖", "客户数", "产品数"));
            for (ExploreResponse.ExploreItem item : result.items()) {
                table.addCell(denseCell(item.name(), Element.ALIGN_LEFT));
                table.addCell(denseCell(item.profitAvailable() ? money(item.comparableRevenue()) : "不可用",
                        Element.ALIGN_RIGHT));
                table.addCell(denseCell(item.profitAvailable() ? money(item.comparableCost()) : "不可用",
                        Element.ALIGN_RIGHT));
                table.addCell(denseCell(item.profitAvailable() ? money(item.profit()) : "不可用",
                        Element.ALIGN_RIGHT));
                table.addCell(denseCell(profitMarginText(item),
                        Element.ALIGN_RIGHT));
                table.addCell(denseCell(percent(item.profitCoverage()), Element.ALIGN_RIGHT));
                table.addCell(denseCell(String.valueOf(item.customerCount()), Element.ALIGN_RIGHT));
                table.addCell(denseCell(String.valueOf(item.productCount()), Element.ALIGN_RIGHT));
            }
            document.add(table);
        }
        if (salesGroups == null) {
            addEmpty(document, "当前报表没有可用销售组字段，无法生成销售组客户与产品下钻。");
            return;
        }
        for (ExploreResponse.ExploreItem group : salesGroups.items().stream().limit(5).toList()) {
            Map<String, List<String>> filter = Map.of("salesGroup", List.of(group.name()));
            ExploreResponse customers = explore(session, "customer", filter, null, costMetric,
                    Math.min(10, topN), request);
            ExploreResponse products = explore(session, "product", filter, null, costMetric,
                    Math.min(10, topN), request);
            if ((customers == null || customers.items().isEmpty()) && (products == null || products.items().isEmpty())) continue;
            boolean subsectionAdded = false;
            if (customers != null && !customers.items().isEmpty()) {
                addSubsectionTable(document, group.name() + " · 经营下钻",
                        "分别核对该销售组的客户贡献和产品利润",
                        "客户情况", compactProfitTable("客户", customers.items()));
                subsectionAdded = true;
            }
            if (products != null && !products.items().isEmpty()) {
                if (subsectionAdded) {
                    addLabeledTable(document, "产品情况", compactProfitTable("产品", products.items()));
                } else {
                    addSubsectionTable(document, group.name() + " · 经营下钻",
                            "分别核对该销售组的客户贡献和产品利润",
                            "产品情况", compactProfitTable("产品", products.items()));
                }
            }
        }
    }

    private ExploreResponse explore(AnalysisSession session, String groupBy,
                                    Map<String, List<String>> filters,
                                    String quantityMetric, String costMetric, int limit,
                                    PdfReportRequest request) {
        try {
            return explorer.explore(session.result().id(), session,
                    new ExploreRequest(groupBy, filters, quantityMetric, costMetric,
                            null, null, null, null, Math.max(1, Math.min(100, limit)),
                            request.periodMode(), request.years(), request.months()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void addSubsection(Document document, String name, String description) throws DocumentException {
        Paragraph label = new Paragraph(name, h2);
        label.setSpacingBefore(15);
        label.setSpacingAfter(2);
        document.add(label);
        Paragraph detail = new Paragraph(description, small);
        detail.setSpacingAfter(6);
        document.add(detail);
    }

    /**
     * Adds the same two perspectives as the desktop product-structure lens without repeating the
     * profit detail that follows later in this chapter. Keeping the heading and the table in one
     * grouped block prevents a lens title from becoming an orphan at the bottom of a page.
     */
    private void addProductStructureLens(Document document, String dimensionLabel,
                                         ExploreResponse result) throws DocumentException {
        BigDecimal totalConverted = result.totals() == null ? null : result.totals().convertedQuantity();
        PdfPTable table = new PdfPTable(new float[]{1.35f, .9f, .65f, .95f, 1.2f, .75f, .95f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        addHeader(table, List.of(dimensionLabel, "换算只数（只）", "数量占比", "换算只数结构",
                "销售额", "销售额占比", "销售额结构"));
        for (ExploreResponse.ExploreItem item : result.items()) {
            BigDecimal quantityShare = share(item.convertedQuantity(), totalConverted);
            table.addCell(denseCell(item.name(), Element.ALIGN_LEFT));
            table.addCell(denseCell(number(item.convertedQuantity()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(percent(quantityShare), Element.ALIGN_RIGHT));
            table.addCell(barCell(decimalFloat(quantityShare), CYAN));
            table.addCell(denseCell(money(item.revenue()), Element.ALIGN_RIGHT));
            table.addCell(denseCell(percent(item.share()), Element.ALIGN_RIGHT));
            table.addCell(barCell(decimalFloat(item.share()), PRIMARY));
        }

        String coverage = result.truncated()
                ? "展示销售额前 " + result.items().size() + " / " + result.totalGroups()
                + " 类；占比仍以本透镜全部数据为分母。"
                : "展示全部 " + result.totalGroups() + " 类；换算只数与销售额分别独立计算占比。";
        addSubsectionTable(document, "产品结构透镜 · " + dimensionLabel,
                coverage + " 数量侧为换算只数 SUM，单位只，不再使用去重产品种类数。",
                null, table);
    }

    private static BigDecimal share(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() == 0) return null;
        return value.divide(total, 4, RoundingMode.HALF_UP);
    }

    private PdfPTable compactProfitTable(String firstColumn, List<ExploreResponse.ExploreItem> items) {
        PdfPTable table = new PdfPTable(new float[]{2f, 1.2f, 1.2f, 1.05f, .8f, .75f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        addHeader(table, List.of(firstColumn, "可比收入", "可比成本", "利润", "利润率", "覆盖"));
        for (ExploreResponse.ExploreItem item : items) {
            table.addCell(denseCell(item.name(), Element.ALIGN_LEFT));
            table.addCell(denseCell(item.profitAvailable() ? money(item.comparableRevenue()) : "不可用",
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(item.profitAvailable() ? money(item.comparableCost()) : "不可用",
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(item.profitAvailable() ? money(item.profit()) : "不可用",
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(profitMarginText(item),
                    Element.ALIGN_RIGHT));
            table.addCell(denseCell(percent(item.profitCoverage()), Element.ALIGN_RIGHT));
        }
        return table;
    }

    private void addSubsectionTable(Document document, String name, String description,
                                    String tableLabel, PdfPTable table) throws DocumentException {
        PdfPTable group = groupedTable();
        PdfPCell cell = group.getRow(0).getCells()[0];
        Paragraph heading = new Paragraph(name, h2);
        heading.setSpacingAfter(2);
        cell.addElement(heading);
        Paragraph detail = new Paragraph(description, small);
        detail.setSpacingAfter(6);
        cell.addElement(detail);
        if (tableLabel != null && !tableLabel.isBlank()) cell.addElement(compactTableLabel(tableLabel));
        cell.addElement(table);
        document.add(group);
    }

    private void addLabeledTable(Document document, String label, PdfPTable table) throws DocumentException {
        PdfPTable group = groupedTable();
        PdfPCell cell = group.getRow(0).getCells()[0];
        cell.addElement(compactTableLabel(label));
        cell.addElement(table);
        document.add(group);
    }

    private PdfPTable groupedTable() {
        PdfPTable group = new PdfPTable(1);
        group.setWidthPercentage(100);
        group.setKeepTogether(true);
        group.setSpacingBefore(15);
        PdfPCell cell = new PdfPCell();
        cell.setPadding(0);
        cell.setBorder(Rectangle.NO_BORDER);
        group.addCell(cell);
        return group;
    }

    private Paragraph compactTableLabel(String value) {
        Paragraph label = new Paragraph(value, font(9, Font.BOLD, PRIMARY));
        label.setSpacingBefore(3);
        label.setSpacingAfter(4);
        return label;
    }

    private static float decimalFloat(BigDecimal value) {
        if (value == null) return 0;
        return Math.max(0, Math.min(1, value.floatValue()));
    }

    private void addInsights(Document document, AnalysisSession session) throws DocumentException {
        sectionTitle(document, "07", "结论与改进建议", "将发现转成可执行动作，并保留证据来源");
        if (session.result().insights().isEmpty()) {
            addEmpty(document, "当前没有可导出的经营建议。");
            return;
        }
        int index = 1;
        for (Insight insight : session.result().insights()) {
            PdfPTable card = new PdfPTable(1);
            card.setWidthPercentage(100);
            card.setSpacingBefore(index == 1 ? 8 : 12);
            PdfPCell cell = new PdfPCell();
            cell.setPadding(12);
            cell.setBorderColor(BORDER);
            cell.setBackgroundColor(SURFACE);
            cell.addElement(new Paragraph(String.format("%02d  %s", index++, insight.title()), h2));
            Paragraph finding = new Paragraph("发现：" + safe(insight.finding()), body);
            finding.setSpacingBefore(6);
            cell.addElement(finding);
            Paragraph action = new Paragraph("建议：" + safe(insight.action()), body);
            action.setSpacingBefore(4);
            cell.addElement(action);
            Paragraph evidence = new Paragraph("证据：" + safe(insight.evidence()), small);
            evidence.setSpacingBefore(5);
            cell.addElement(evidence);
            card.addCell(cell);
            document.add(card);
        }
    }

    private void addQuality(Document document, AnalysisSession session) throws DocumentException {
        sectionTitle(document, "08", "数据质量与口径附录", "说明缺失、异常、覆盖率和不可用指标");
        QualityReport q = session.result().quality();
        List<String[]> rows = List.of(
                new String[]{"数据总行数", String.valueOf(q.totalRows()), "导入的明细记录"},
                new String[]{"有效行数", String.valueOf(q.validRows()), "进入能力判断的记录"},
                new String[]{"缺少日期", String.valueOf(q.missingDate()), "归入未指定日期"},
                new String[]{"缺少产品", String.valueOf(q.missingProduct()), "归入未指定产品"},
                new String[]{"缺少客户", String.valueOf(q.missingCustomer()), "归入未指定客户"},
                new String[]{"无法解析数值", String.valueOf(q.invalidNumericCells()), "不会自动按 0 计入利润"}
        );
        document.add(threeColumnTable(List.of("检查项", "结果", "说明"), rows, new float[]{1.4f, 1, 2.7f}));
        if (!q.warnings().isEmpty()) {
            Paragraph warnings = new Paragraph("质量提示", h2);
            warnings.setSpacingBefore(14);
            warnings.setSpacingAfter(4);
            document.add(warnings);
            for (String warning : q.warnings()) addBullet(document, warning, ORANGE);
        }
        addSubsection(document, "报告结论证据索引",
                "逐条列出报告洞察引用的证据；洞察可来自本地规则或已配置 AI，金额仍由本地程序计算");
        List<Insight> insights = session.result().insights();
        if (insights.isEmpty()) {
            addEmpty(document, "当前报告没有洞察结论，暂无 AI 证据可列入索引。");
        } else {
            List<String[]> evidenceRows = new ArrayList<>();
            for (int index = 0; index < insights.size(); index++) {
                Insight insight = insights.get(index);
                evidenceRows.add(new String[]{String.format("%02d", index + 1), safe(insight.title()),
                        insight.evidence() == null || insight.evidence().isBlank()
                                ? "未提供独立证据引用" : insight.evidence().strip()});
            }
            document.add(threeColumnTable(List.of("序号", "结论标题", "证据引用"), evidenceRows,
                    new float[]{.55f, 1.75f, 3.4f}));
        }
        Paragraph rule = new Paragraph("口径原则", h2);
        rule.setSpacingBefore(16);
        rule.setSpacingAfter(5);
        document.add(rule);
        addBullet(document, "利润仅使用收入与相关成本、费用同时有效的可比行计算。", PRIMARY);
        addBullet(document, "公式错误、空值和无法解析的数值不会按 0 补入。", PRIMARY);
        addBullet(document, "AI 仅用于字段理解、经营解释和行动建议；金额由程序计算。", PRIMARY);
    }

    private boolean sectionPage(Document document, boolean firstSection) {
        // The cover is intentionally a standalone decision page. Every selected chapter starts
        // on a fresh page so a short, custom report still reads like a finished document.
        document.setPageSize(PageSize.A4);
        document.newPage();
        return false;
    }

    private void sectionTitle(Document document, String index, String name, String description)
            throws DocumentException {
        Paragraph kicker = new Paragraph(index + " / DATAMASTER", font(8, Font.BOLD, PRIMARY));
        kicker.setSpacingAfter(5);
        document.add(kicker);
        Paragraph heading = new Paragraph(name, h1);
        heading.setLeading(24);
        document.add(heading);
        Paragraph detail = new Paragraph(description, bodyMuted);
        detail.setSpacingBefore(3);
        detail.setSpacingAfter(12);
        document.add(detail);
        document.add(new LineSeparator(.6f, 100, BORDER, Element.ALIGN_LEFT, 0));
    }

    private PdfPCell metaCell(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(10);
        cell.setBorderColor(BORDER);
        cell.setBackgroundColor(SURFACE);
        cell.addElement(new Paragraph(label, small));
        Paragraph content = new Paragraph(value, h2);
        content.setSpacingBefore(4);
        cell.addElement(content);
        return cell;
    }

    private PdfPCell kpiCell(String label, String value, Color accent) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(12);
        cell.setBorderColor(BORDER);
        cell.setBorderWidthTop(3);
        cell.setBorderColorTop(accent);
        cell.addElement(new Paragraph(label, bodyMuted));
        Paragraph content = new Paragraph(value, metric);
        content.setSpacingBefore(6);
        cell.addElement(content);
        return cell;
    }

    private PdfPTable threeColumnTable(List<String> headers, List<String[]> rows, float[] widths)
            throws DocumentException {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        table.setHeaderRows(1);
        addHeader(table, headers);
        for (String[] row : rows) {
            for (int index = 0; index < headers.size(); index++) {
                table.addCell(textCell(index < row.length ? row[index] : "", body,
                        index == 0 || index == headers.size() - 1 && headers.size() == 3
                                ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
            }
        }
        return table;
    }

    private void addHeader(PdfPTable table, List<String> values) {
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Phrase(value, smallWhite));
            cell.setPadding(7);
            cell.setBorderColor(PRIMARY);
            cell.setBackgroundColor(PRIMARY);
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(cell);
        }
    }

    private PdfPCell textCell(String value, Font valueFont, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), valueFont));
        cell.setPadding(7);
        cell.setBorderColor(BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell denseCell(String value, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), dense));
        cell.setPadding(5);
        cell.setBorderColor(BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell barCell(float ratio, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(" ", small));
        cell.setPadding(7);
        cell.setMinimumHeight(24);
        cell.setBorderColor(BORDER);
        cell.setCellEvent(new BarCellEvent(Math.max(0, Math.min(1, ratio)), color));
        return cell;
    }

    private void addBullet(Document document, String value, Color color) throws DocumentException {
        Paragraph line = new Paragraph();
        line.setLeading(14);
        line.setSpacingBefore(3);
        line.add(new Chunk("- ", font(9, Font.BOLD, color)));
        line.add(new Chunk(safe(value), body));
        document.add(line);
    }

    private void addEmpty(Document document, String message) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(12);
        PdfPCell cell = textCell(message, bodyMuted, Element.ALIGN_CENTER);
        cell.setPadding(20);
        cell.setBackgroundColor(SURFACE);
        table.addCell(cell);
        document.add(table);
    }

    private Font font(float size, int style, Color color) {
        return new Font(base, size, style, color);
    }

    private static FontSelection resolveChineseFont() {
        Set<FontCandidate> candidates = new LinkedHashSet<>();
        addFontCandidate(candidates, System.getProperty(FONT_PROPERTY));
        addFontCandidate(candidates, System.getenv(FONT_ENV));

        // macOS system fonts. Hiragino Sans GB is preferred because it has dependable Simplified
        // Chinese coverage and OpenPDF can subset/embed it from the TrueType collection.
        addFontCandidate(candidates, "/System/Library/Fonts/Hiragino Sans GB.ttc");
        addFontCandidate(candidates, "/System/Library/Fonts/PingFang.ttc");
        addFontCandidate(candidates, "/System/Library/Fonts/STHeiti Medium.ttc");
        addFontCandidate(candidates, "/System/Library/Fonts/STHeiti Light.ttc");
        addFontCandidate(candidates, "/System/Library/Fonts/Supplemental/Songti.ttc");

        // Common Linux distributions and container images.
        addFontCandidate(candidates, "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        addFontCandidate(candidates, "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf");
        addFontCandidate(candidates, "/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf");
        addFontCandidate(candidates, "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc");
        addFontCandidate(candidates, "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc");
        addFontCandidate(candidates, "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc");
        addFontCandidate(candidates, "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf");
        addFontCandidate(candidates, "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc");
        addFontCandidate(candidates, "/usr/share/fontss/noto-cjk/NotoSansCJK-Regular.ttc");

        // Windows fonts are rooted at SystemRoot/WINDIR rather than assuming the C: drive.
        String windowsRoot = firstNonBlank(System.getenv("SystemRoot"), System.getenv("WINDIR"));
        if (windowsRoot != null) {
            Path fonts = Path.of(windowsRoot, "Fonts");
            for (String name : List.of("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "simsun.ttc",
                    "deng.ttf", "msjh.ttc", "mingliu.ttc")) {
                addFontCandidate(candidates, fonts.resolve(name).toString());
            }
        }

        for (FontCandidate candidate : candidates) {
            FontSelection selection = loadEmbeddedFont(candidate);
            if (selection != null) return selection;
        }

        // Font package layouts vary between Linux distributions. A bounded scan of font-only
        // directories catches Source Han, Noto and WenQuanYi variants without searching the disk.
        for (Path discovered : discoverCjkFonts(windowsRoot)) {
            FontSelection selection = loadEmbeddedFont(fontCandidate(discovered.toString()));
            if (selection != null) return selection;
        }

        try {
            // Last-resort compatibility fallback. It preserves Chinese rendering in viewers with
            // the standard CJK resources, but unlike the preferred path it cannot guarantee the
            // same glyphs on every device because no font program is embedded.
            BaseFont fallback = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            return new FontSelection(fallback, "OpenPDF STSong-Light CMap fallback", false);
        } catch (Exception ex) {
            throw new IllegalStateException("初始化 PDF 中文字体失败", ex);
        }
    }

    private static FontSelection loadEmbeddedFont(FontCandidate candidate) {
        if (candidate == null || !Files.isRegularFile(candidate.path())) return null;
        try {
            BaseFont font = BaseFont.createFont(candidate.openPdfSpec(), BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, BaseFont.NOT_CACHED);
            if (!font.isEmbedded() || !BaseFont.IDENTITY_H.equals(font.getEncoding())) return null;
            for (int glyph : REQUIRED_CJK_GLYPHS) {
                if (!font.charExists(glyph)) return null;
            }
            font.setSubset(true);
            return new FontSelection(font, candidate.path().toString(), true);
        } catch (Exception ignored) {
            // Some OS fonts disallow embedding or use a collection format unsupported by the
            // current OpenPDF version. Continue to the next known CJK font in that case.
            return null;
        }
    }

    private static void addFontCandidate(Set<FontCandidate> candidates, String value) {
        FontCandidate candidate = fontCandidate(value);
        if (candidate != null) candidates.add(candidate);
    }

    private static FontCandidate fontCandidate(String value) {
        if (value == null || value.isBlank()) return null;
        String rawPath = value.strip();
        int collectionIndex = -1;
        int separator = rawPath.lastIndexOf(',');
        try {
            if (separator > 0 && separator < rawPath.length() - 1
                    && isDigits(rawPath.substring(separator + 1))) {
                collectionIndex = Integer.parseInt(rawPath.substring(separator + 1));
                rawPath = rawPath.substring(0, separator);
            }
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (collectionIndex < 0 && lower.endsWith(".ttc")) collectionIndex = 0;
            return new FontCandidate(path, collectionIndex);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<Path> discoverCjkFonts(String windowsRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of("/System/Library/Fonts"));
        roots.add(Path.of("/Library/Fonts"));
        roots.add(Path.of("/usr/share/fonts"));
        roots.add(Path.of("/usr/local/share/fonts"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            roots.add(Path.of(home, "Library", "Fonts"));
            roots.add(Path.of(home, ".local", "share", "fonts"));
            roots.add(Path.of(home, ".fonts"));
        }
        if (windowsRoot != null) roots.add(Path.of(windowsRoot, "Fonts"));

        List<Path> fonts = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.find(root, 5,
                    (path, attributes) -> attributes.isRegularFile() && isCjkFontFile(path))) {
                files.sorted(Comparator.comparingInt(PdfReportService::fontPriority)
                                .thenComparing(Path::toString))
                        .limit(32)
                        .forEach(fonts::add);
            } catch (Exception ignored) {
                // A protected system font directory must not prevent report generation.
            }
        }
        return fonts;
    }

    private static boolean isCjkFontFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc"))) return false;
        return name.contains("notosanscjk") || name.contains("notosanssc")
                || name.contains("notoserifcjk") || name.contains("sourcehansans")
                || name.contains("sourcehanserif") || name.contains("sarasa")
                || name.contains("wenquanyi") || name.contains("wqy")
                || name.contains("droidsansfallback") || name.contains("hiragino")
                || name.contains("pingfang") || name.contains("stheiti")
                || name.contains("songti") || name.contains("yahei") || name.contains("msyh")
                || name.contains("simhei") || name.contains("simsun") || name.contains("deng")
                || name.contains("msjh") || name.contains("mingliu");
    }

    private static int fontPriority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("notosans") || name.contains("sourcehansans")) return 0;
        if (name.contains("hiragino") || name.contains("pingfang") || name.contains("yahei")) return 1;
        if (name.contains("wqy") || name.contains("droidsansfallback")) return 2;
        return 3;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return false;
        }
        return true;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private static String money(BigDecimal value) {
        if (value == null) return "-";
        return "¥" + String.format(java.util.Locale.US, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String number(BigDecimal value) {
        if (value == null) return "-";
        return String.format(java.util.Locale.US, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String percent(BigDecimal value) {
        if (value == null) return "-";
        return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static String marginText(BigDecimal value) {
        return value == null ? "不适用" : percent(value);
    }

    private static String profitMarginText(ExploreResponse.ExploreItem item) {
        if (!item.profitAvailable()) return "不可用";
        if (item.comparableRevenue() == null || item.comparableRevenue().signum() <= 0) return "不适用";
        return marginText(item.margin());
    }

    private static float ratio(BigDecimal value, BigDecimal max) {
        if (value == null || max == null || max.signum() == 0) return 0;
        return value.max(BigDecimal.ZERO).divide(max, 4, RoundingMode.HALF_UP).floatValue();
    }

    private static String sectionName(String value) {
        return switch (value) {
            case "overview" -> "经营总览";
            case "trend" -> "月度趋势";
            case "product" -> "产品分析";
            case "customer" -> "客户分析";
            case "profit" -> "利润与成本";
            case "organization" -> "组织与分类";
            case "insights" -> "结论建议";
            case "quality" -> "质量附录";
            default -> value;
        };
    }

    private static String periodLabel(PdfReportRequest request) {
        String mode = switch (request.periodMode() == null ? "cumulative" : request.periodMode().toLowerCase(Locale.ROOT)) {
            case "year" -> "年度分析";
            case "month" -> "月度分析";
            default -> "累计分析";
        };
        List<String> selected = !request.months().isEmpty() ? request.months() : request.years();
        return selected.isEmpty() ? mode + "（全部期间）" : mode + "（" + String.join("、", selected) + "）";
    }

    private record FontCandidate(Path path, int collectionIndex) {
        private String openPdfSpec() {
            return path + (collectionIndex >= 0 ? "," + collectionIndex : "");
        }
    }

    private record FontSelection(BaseFont base, String source, boolean embeddedUnicode) {
    }

    private record ProfitSnapshot(BigDecimal grossRevenue, BigDecimal grossCost, long grossRows,
                                  BigDecimal operatingRevenue, BigDecimal operatingCost,
                                  BigDecimal operatingExpenses, long operatingRows) {
    }

    private static final class BarCellEvent implements PdfPCellEvent {
        private final float ratio;
        private final Color color;

        private BarCellEvent(float ratio, Color color) {
            this.ratio = ratio;
            this.color = color;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            float left = position.getLeft() + 6;
            float bottom = position.getBottom() + 8;
            float width = Math.max(1, position.getWidth() - 12);
            float height = Math.max(4, position.getHeight() - 16);
            canvas.saveState();
            canvas.setColorFill(PRIMARY_SOFT);
            canvas.roundRectangle(left, bottom, width, height, height / 2);
            canvas.fill();
            if (ratio > 0) {
                canvas.setColorFill(color);
                canvas.roundRectangle(left, bottom, Math.max(height, width * ratio), height, height / 2);
                canvas.fill();
            }
            canvas.restoreState();
        }
    }

    private static final class PageChrome extends PdfPageEventHelper {
        private final Font footer;

        private PageChrome(BaseFont base) {
            this.footer = new Font(base, 7.5f, Font.NORMAL, MUTED);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            Rectangle page = writer.getPageSize();
            float left = page.getLeft() + document.leftMargin();
            float right = page.getRight() - document.rightMargin();
            canvas.saveState();
            canvas.setColorStroke(BORDER);
            canvas.setLineWidth(.5f);
            canvas.moveTo(left, 31);
            canvas.lineTo(right, 31);
            canvas.stroke();
            canvas.restoreState();
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                    new Phrase("DataMaster · 本地经营分析", footer), left, 19, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT,
                    new Phrase("第 " + writer.getPageNumber() + " 页", footer), right, 19, 0);
        }
    }
}
