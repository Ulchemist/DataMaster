package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.InsightSource;
import com.datamaster.app.domain.PdfReportRequest;
import com.datamaster.app.support.AnalysisFixtures;
import com.lowagie.text.pdf.PdfArray;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStream;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.Rectangle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfReportServiceTest {
    private static final PdfName IDENTITY_H = new PdfName("Identity-H");

    @Test
    void createsReadableCompleteChineseReport() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);

        byte[] bytes = new PdfReportService().create(analysis.requireSession(sample.id()),
                PdfReportRequest.complete());

        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(5_000);
        try (PdfReader reader = new PdfReader(bytes)) {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(5);
            assertThat(reader.getInfo()).containsEntry("Title", "DataMaster 经营分析报告")
                    .containsEntry("Author", "DataMaster");
            assertThat(reader.getPageContent(1)).isNotEmpty();
        }
    }

    @Test
    void honorsSelectedSectionsAndTopN() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);
        PdfReportRequest request = new PdfReportRequest("结构专项复盘",
                List.of("product", "customer", "organization", "quality"), 5);

        byte[] bytes = new PdfReportService().create(analysis.requireSession(sample.id()), request);

        byte[] complete = new PdfReportService().create(analysis.requireSession(sample.id()),
                PdfReportRequest.complete());
        try (PdfReader reader = new PdfReader(bytes); PdfReader completeReader = new PdfReader(complete)) {
            assertThat(reader.getInfo()).containsEntry("Title", "结构专项复盘");
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(3)
                    .isLessThan(completeReader.getNumberOfPages());
        }
        assertThat(extractAll(bytes)).contains("产品分析", "客户分析", "销售组织与阿米巴利润",
                "可比收入", "可比成本", "利润率", "覆盖");
    }

    @Test
    void createsExtractableStandaloneProfitAndCostChapter() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);

        byte[] bytes = new PdfReportService().create(analysis.requireSession(sample.id()),
                new PdfReportRequest("利润专项复盘", List.of("profit"), 10));
        String text = extractAll(bytes);

        assertThat(text).contains("利润与成本", "毛利可比口径", "可比收入", "可比成本",
                        "毛利润", "毛利率", "经营可比费用", "经营利润", "距盈亏平衡")
                .doesNotContain("产品分析", "客户分析", "结论与改进建议");
    }

    @Test
    void standaloneProfitChapterStatesWhenExpensesAreUnavailable() throws Exception {
        String csv = "日期,产品,客户,收入,成本\n"
                + "2026-06-01,产品A,客户甲,1200,700\n"
                + "2026-06-02,产品B,客户乙,800,500\n";
        MockMultipartFile file = new MockMultipartFile("files", "无费用.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        SpreadsheetImportService importer = new SpreadsheetImportService(10_000);
        AnalysisService analysis = new AnalysisService();
        AnalysisResult result = analysis.analyze(importer.importFiles(new MockMultipartFile[]{file}));

        byte[] bytes = new PdfReportService().create(analysis.requireSession(result.id()),
                new PdfReportRequest("无费用利润复盘", List.of("profit"), 10));
        String text = extractAll(bytes);

        assertThat(text).contains("利润与成本", "可比收入", "可比成本", "毛利润", "毛利率",
                "期间费用不可用", "经营利润与盈亏平衡不可用");
    }

    @Test
    void productChapterMarksNegativeRevenueMarginAsNotApplicable() throws Exception {
        String csv = "日期,产品,客户,收入,成本\n"
                + "2026-06-01,正常产品,客户甲,1000,600\n"
                + "2026-06-02,商业折扣,客户乙,-100,-5\n";
        MockMultipartFile file = new MockMultipartFile("files", "负收入.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        SpreadsheetImportService importer = new SpreadsheetImportService(10_000);
        AnalysisService analysis = new AnalysisService();
        AnalysisResult result = analysis.analyze(importer.importFiles(new MockMultipartFile[]{file}));

        String text = extractAll(new PdfReportService().create(analysis.requireSession(result.id()),
                new PdfReportRequest("负收入利润率复盘", List.of("product"), 10)));

        assertThat(text).contains("商业折扣", "不适用").doesNotContain("95.0%");
    }

    @Test
    void productChapterExportsThreeSearchableStructureLensesWithCountAndRevenueShares() throws Exception {
        String csv = "日期,产品,客户,销售数量,换算只数,收入,成本,经营分析大类,经营分析分类,产品形态（汇报）\n"
                + "2026-06-01,产品A,客户甲,40,20,100,60,禽类,清远鸡,鲜品\n"
                + "2026-06-02,产品B,客户甲,60,30,200,120,禽类,清远鸡,鲜品\n"
                + "2026-06-03,产品C,客户乙,50,30,300,210,副产品,分割品,冻品\n"
                + "2026-06-04,产品D,客户丙,40,20,400,280,熟食,熟食,熟食\n";
        MockMultipartFile file = new MockMultipartFile("files", "产品结构透镜.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        SpreadsheetImportService importer = new SpreadsheetImportService(10_000);
        AnalysisService analysis = new AnalysisService();
        AnalysisResult result = analysis.analyze(importer.importFiles(new MockMultipartFile[]{file}));

        byte[] bytes = new PdfReportService().create(analysis.requireSession(result.id()),
                new PdfReportRequest("产品结构透镜复盘", List.of("product"), 20));
        String text = extractAll(bytes);

        assertThat(text).contains("产品结构透镜 · 经营分析大类", "产品结构透镜 · 经营分析分类",
                "产品结构透镜 · 产品形态（汇报）", "换算只数结构", "数量占比",
                "销售额结构", "销售额占比", "50.0%", "30.0%", "展示全部",
                "产品明细 · 量价利同屏", "销售数", "换算只", "平均单", "平均成",
                "单只毛", "可比成本", "毛利", "覆盖");

        for (String page : extractPages(bytes)) {
            if (!page.contains("产品结构透镜 · ")) continue;
            assertThat(page).as("透镜标题不得与表头孤立分页")
                    .contains("换算只数结构", "销售额结构");
        }
        try (PdfReader reader = new PdfReader(bytes)) {
            boolean foundLandscapeDetail = false;
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int pageNumber = 1; pageNumber <= reader.getNumberOfPages(); pageNumber++) {
                if (!extractor.getTextFromPage(pageNumber).contains("产品明细 · 量价利同屏")) continue;
                Rectangle size = reader.getPageSizeWithRotation(pageNumber);
                foundLandscapeDetail = size.getWidth() > size.getHeight();
                break;
            }
            assertThat(foundLandscapeDetail).as("11 项产品明细应使用横向页面保证可读性").isTrue();
        }
    }

    @Test
    void selectedPdfScopeUsesOnlyTheChosenMonthAcrossOverviewTrendAndProfit() throws Exception {
        String csv = "日期,产品,客户,销售数量,换算只数,收入,成本,期间费用\n"
                + "2026-05-01,产品A,客户甲,40,30,400,240,20\n"
                + "2026-06-01,产品B,客户乙,100,60,600,300,30\n";
        MockMultipartFile file = new MockMultipartFile("files", "月度范围.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        AnalysisService analysis = new AnalysisService();
        AnalysisResult result = analysis.analyze(new SpreadsheetImportService(10_000)
                .importFiles(new MockMultipartFile[]{file}));
        PdfReportRequest request = new PdfReportRequest("六月专项复盘",
                List.of("overview", "trend", "profit"), 10,
                "month", List.of("2026"), List.of("2026-06"));

        String text = extractAll(new PdfReportService().create(analysis.requireSession(result.id()), request));

        assertThat(text).contains("期间口径：月度分析（2026-06）", "¥600.00", "¥300.00",
                        "当前期间毛利口径", "2026-06")
                .doesNotContain("¥1,000.00", "2026-05");
    }

    @Test
    void selectedPdfScopeDoesNotLeakFullWorkspaceInsightsOrQualityCounts() throws Exception {
        String csv = "日期,产品,客户,收入,成本\n"
                + "2026-05-01,产品A,客户甲,400,240\n"
                + "2026-06-01,产品B,客户乙,600,300\n";
        MockMultipartFile file = new MockMultipartFile("files", "洞察范围.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        AnalysisService analysis = new AnalysisService();
        AnalysisResult result = analysis.analyze(new SpreadsheetImportService(10_000)
                .importFiles(new MockMultipartFile[]{file}));
        analysis.updateInsights(result.id(), List.of(new com.datamaster.app.domain.Insight(
                        "全量AI旧结论", "只适用于两个月累计", "不应出现在六月报告", "全量证据")),
                InsightSource.AI, "deepseek", "model", Instant.now());
        PdfReportRequest request = new PdfReportRequest("六月隔离报告",
                List.of("overview", "insights", "quality"), 10,
                "month", List.of("2026"), List.of("2026-06"));

        String text = extractAll(new PdfReportService().create(analysis.requireSession(result.id()), request));

        assertThat(text).contains("分析明细", "1 行", "数据总行数", "有效行数", "六月隔离报告")
                .doesNotContain("全量AI旧结论", "只适用于两个月累计", "全量证据", "2 行");
    }

    @Test
    void profitChapterPrefersPersistedComparableAmountsOverRecomputedRows() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);
        FinancialSummary source = sample.summary();
        FinancialSummary explicit = new FinancialSummary(source.revenue(), source.cost(),
                new BigDecimal("1234.00"), new BigDecimal("1000.00"), new BigDecimal("234.00"),
                source.expenses(), new BigDecimal("1200.00"), new BigDecimal("900.00"),
                new BigDecimal("100.00"), new BigDecimal("200.00"),
                new BigDecimal("0.1896"), new BigDecimal("0.1667"));
        AnalysisResult overridden = withSummary(sample, explicit);
        AnalysisSession session = analysis.requireSession(sample.id()).withResult(overridden);

        String text = extractAll(new PdfReportService().create(session,
                new PdfReportRequest("显式可比口径复盘", List.of("profit"), 10)));

        assertThat(text).contains("¥1,234.00", "¥1,000.00", "¥1,200.00", "¥900.00", "¥100.00");
    }

    @Test
    void legacyNullComparableAmountsFallBackToProfitDividedByMargin() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);
        FinancialSummary source = sample.summary();
        FinancialSummary legacy = new FinancialSummary(source.revenue(), source.cost(),
                null, null, new BigDecimal("100.00"), source.expenses(),
                null, null, null, source.operatingProfit(), new BigDecimal("0.25"), source.operatingMargin());
        AnalysisResult overridden = withSummary(sample, legacy);
        AnalysisSession session = analysis.requireSession(sample.id()).withResult(overridden);

        String text = extractAll(new PdfReportService().create(session,
                new PdfReportRequest("旧工作区可比口径复盘", List.of("profit"), 10)));

        assertThat(text).contains("可比收入", "¥400.00", "可比成本", "¥300.00");
    }

    @Test
    void qualityChapterContainsExtractableInsightEvidenceIndexAndEmptyState() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);
        byte[] indexedBytes = new PdfReportService().create(analysis.requireSession(sample.id()),
                new PdfReportRequest("证据专项复盘", List.of("quality"), 10));
        String indexedText = extractAll(indexedBytes);

        assertThat(indexedText).contains("报告结论证据索引",
                sample.insights().get(0).title(), sample.insights().get(0).evidence());

        analysis.updateInsights(sample.id(), List.of(), InsightSource.LOCAL_RULES,
                null, null, Instant.now());
        byte[] emptyBytes = new PdfReportService().create(analysis.requireSession(sample.id()),
                new PdfReportRequest("空证据专项复盘", List.of("quality"), 10));

        assertThat(extractAll(emptyBytes)).contains("报告结论证据索引", "暂无 AI 证据可列入索引");
    }

    @Test
    void embedsUnicodeChineseFontForPortableReports() throws Exception {
        AnalysisService analysis = new AnalysisService();
        AnalysisResult sample = AnalysisFixtures.completeAnalysis(analysis);
        PdfReportService service = new PdfReportService();
        Assumptions.assumeTrue(service.usesEmbeddedUnicodeFont(),
                "运行环境没有可嵌入的中文字体，已使用兼容回退：" + service.selectedFontSource());

        byte[] bytes = service.create(analysis.requireSession(sample.id()), PdfReportRequest.complete());

        boolean identityH = false;
        boolean embedded = false;
        boolean toUnicode = false;
        try (PdfReader reader = new PdfReader(bytes)) {
            for (int pageNumber = 1; pageNumber <= reader.getNumberOfPages(); pageNumber++) {
                PdfDictionary resources = dictionary(reader.getPageN(pageNumber).get(PdfName.RESOURCES));
                PdfDictionary fonts = resources == null ? null : dictionary(resources.get(PdfName.FONT));
                if (fonts == null) continue;
                for (PdfName key : fonts.getKeys()) {
                    PdfDictionary font = dictionary(fonts.get(key));
                    if (font == null || !IDENTITY_H.equals(font.getAsName(PdfName.ENCODING))) continue;
                    identityH = true;
                    toUnicode |= PdfReader.getPdfObject(font.get(PdfName.TOUNICODE)) instanceof PdfStream;

                    PdfArray descendants = array(font.get(PdfName.DESCENDANTFONTS));
                    PdfDictionary descendant = descendants == null || descendants.isEmpty()
                            ? null : dictionary(descendants.getPdfObject(0));
                    PdfDictionary descriptor = descendant == null
                            ? null : dictionary(descendant.get(PdfName.FONTDESCRIPTOR));
                    embedded |= hasEmbeddedFontProgram(descriptor);
                }
            }

            String extracted = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(extracted).contains("经营分析报告");
        }
        assertThat(identityH).as("PDF 字体应使用 Identity-H Unicode 编码").isTrue();
        assertThat(embedded).as("PDF 应包含字体程序流，不能依赖阅读设备字体").isTrue();
        assertThat(toUnicode).as("PDF 应包含 ToUnicode 映射以支持复制和检索中文").isTrue();
    }

    private static PdfDictionary dictionary(PdfObject object) {
        PdfObject direct = PdfReader.getPdfObject(object);
        return direct instanceof PdfDictionary dictionary ? dictionary : null;
    }

    private static AnalysisResult withSummary(AnalysisResult source, FinancialSummary summary) {
        return new AnalysisResult(source.id(), source.sourceFileCount(), source.rowCount(), summary,
                source.quality(), source.products(), source.customers(), source.monthly(), source.insights(),
                source.insightSource(), source.insightProviderId(), source.insightModel(),
                source.insightsGeneratedAt(), source.profile(), source.capabilities(), source.dynamicBreakdowns());
    }

    private static PdfArray array(PdfObject object) {
        PdfObject direct = PdfReader.getPdfObject(object);
        return direct instanceof PdfArray array ? array : null;
    }

    private static boolean hasEmbeddedFontProgram(PdfDictionary descriptor) {
        if (descriptor == null) return false;
        return isStream(descriptor.get(PdfName.FONTFILE))
                || isStream(descriptor.get(PdfName.FONTFILE2))
                || isStream(descriptor.get(PdfName.FONTFILE3));
    }

    private static boolean isStream(PdfObject object) {
        return PdfReader.getPdfObject(object) instanceof PdfStream;
    }

    private static String extractAll(byte[] bytes) throws Exception {
        try (PdfReader reader = new PdfReader(bytes)) {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }

    private static List<String> extractPages(byte[] bytes) throws Exception {
        try (PdfReader reader = new PdfReader(bytes)) {
            java.util.ArrayList<String> pages = new java.util.ArrayList<>();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(extractor.getTextFromPage(page));
            }
            return pages;
        }
    }
}
