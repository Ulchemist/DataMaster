package com.datamaster.app.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpreadsheetImportServiceTest {
    private final SpreadsheetImportService importer = new SpreadsheetImportService(1_000_000);

    @Test
    void recognizesProductSpecAndDeliveryAddressAsDimensions() {
        String csv = "业务日期,物料名称,规格型号,客户系统,送货地址,销售额,成本\n"
                + "2026-06-01,清远鸡（盒）,400g/整只/气调盒/鲜品,山姆,深圳市南山区,1000,600\n";
        MockMultipartFile file = new MockMultipartFile("files", "spec-address.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});
        var row = result.rows().get(0);

        assertThat(row.dimensions())
                .containsEntry("productSpec", "400g/整只/气调盒/鲜品")
                .containsEntry("deliveryAddress", "深圳市南山区");
    }

    @Test
    void recognizesBusinessCustomerOrganisationAndSelectableMeasureSemanticsSeparately() {
        String csv = "业务日期,物料名称,客户系统,客户分析分类,大类,经营分析分类,经营分析大类,产品形态,产品形态（汇报）,销售区域,销售部门,销售组,渠道,出库数量,换算只数,销售额,调拨成本（含运费）\n"
                + "2026-06-01,水饺,客户甲,重点客户,连锁,冷冻,速冻食品,袋装,零售包装,华东,销售一部,A组,直营,10,120,1000,620\n";
        MockMultipartFile file = new MockMultipartFile("files", "dimensions.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});
        var row = result.rows().get(0);

        assertThat(row.dimensions())
                .containsEntry("customerAnalysisCategory", "重点客户")
                .containsEntry("customerAnalysisLargeCategory", "连锁")
                .containsEntry("businessAnalysisCategory", "冷冻")
                .containsEntry("businessAnalysisLargeCategory", "速冻食品")
                .containsEntry("productForm", "零售包装")
                .containsEntry("region", "华东")
                .containsEntry("department", "销售一部")
                .containsEntry("salesGroup", "A组")
                .containsEntry("channel", "直营");
        assertThat(row.measure("outboundQuantity")).isEqualByComparingTo("10");
        assertThat(row.measure("convertedQuantity")).isEqualByComparingTo("120");
        assertThat(row.measure("transferCost")).isEqualByComparingTo("620");
        assertThat(row.metricValid("outboundQuantity")).isTrue();
        assertThat(row.metricValid("convertedQuantity")).isTrue();
        assertThat(row.metricValid("transferCost")).isTrue();
        assertThat(result.profile().availableDimensions()).contains(
                "customerAnalysisCategory", "customerAnalysisLargeCategory",
                "businessAnalysisCategory", "businessAnalysisLargeCategory", "productForm");
        assertThat(result.profile().availableMetrics()).contains(
                "outboundQuantity", "convertedQuantity", "transferCost");
    }

    @Test
    void genericLargeCategoryOnlyMapsToCustomerWhenAdjacentToCustomerAnalysisCategory() {
        String csv = "日期,产品,客户,大类,收入\n2026-06-01,水饺,客户甲,产品大类,1000\n";
        MockMultipartFile file = new MockMultipartFile("files", "generic-category.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});

        assertThat(result.profile().availableDimensions()).doesNotContain("customerAnalysisLargeCategory");
        assertThat(result.rows().get(0).dimensions()).doesNotContainKey("customerAnalysisLargeCategory");
    }

    @Test
    void mergesExcelAndCsvUsingChineseAndEnglishAliases() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "销售一月.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook());
        String csv = "date,product,customer,qty,sales,cogs,expenses\n"
                + "2026-02-01,产品B,客户乙,3,2000,1200,500\n";
        MockMultipartFile csvFile = new MockMultipartFile("files", "sales-feb.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel, csvFile});

        assertThat(result.sourceFileCount()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).product()).isEqualTo("产品A");
        assertThat(result.rows().get(0).revenue()).isEqualByComparingTo("1000");
        assertThat(result.rows().get(1).customer()).isEqualTo("客户乙");
        assertThat(result.rows().get(1).cost()).isEqualByComparingTo("1200");
        assertThat(result.quality().invalidNumericCells()).isZero();
    }

    @Test
    void preservesBlankCustomerAndWarnsAboutDuplicatesAndNegativeRows() {
        String csv = "业务日期,产品名称,客户名称,销售数量,销售额,营业成本,期间费用\n"
                + "2026-03-01,退货品,, -1,-100,-60,0\n"
                + "2026-03-02,重复品,客户甲,2,500,300,50\n"
                + "2026-03-02,重复品,客户甲,2,500,300,50\n";
        MockMultipartFile csvFile = new MockMultipartFile("files", "quality.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{csvFile});

        assertThat(result.rows().get(0).customer()).isNull();
        assertThat(result.quality().missingCustomer()).isEqualTo(1);
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("完全重复") && value.contains("1"));
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("负数") && value.contains("1"));
    }

    @Test
    void skipsStructuralNoiseThatOnlyContainsAnUnmappedHelperCell() {
        String csv = "日期,产品,客户,销售额,成本,单价\n"
                + "2026-04-01,产品A,客户甲,1000,600,10\n"
                + ",,,,,19.9538\n";
        MockMultipartFile file = new MockMultipartFile("files", "structural-noise.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(row.revenue()).isEqualByComparingTo("1000");
        });
        assertThat(result.quality().warnings()).anyMatch(value ->
                value.contains("已跳过 1 行结构噪声") && value.contains("核心指标均为空"));
    }

    @Test
    void retainsDimensionlessAdjustmentWhenAnySelectedMetricHasAValue() {
        String csv = "日期,产品,客户,销售额,成本,单价\n,,,100,,19.9538\n";
        MockMultipartFile file = new MockMultipartFile("files", "adjustment.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.date()).isNull();
            assertThat(row.product()).isNull();
            assertThat(row.customer()).isNull();
            assertThat(row.revenue()).isEqualByComparingTo("100");
        });
    }

    @Test
    void keepsInvalidDateBusinessRowsAndUsesExplicitSourceMonthForMonthlyPlacement() {
        String csv = "日期,产品,客户,销售额,成本\n调整,产品A,亏损调整,0,-600\n";
        MockMultipartFile file = new MockMultipartFile("files", "2026年3月销售数据汇总.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{file});

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(row.cost()).isEqualByComparingTo("-600");
        });
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("日期无法直接解析"));
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("文件名中的明确年月"));
    }

    @Test
    void mergesEveryNonEmptyWorksheetInOneWorkbook() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "多工作表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", multiSheetWorkbook());

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel});

        assertThat(result.sourceFileCount()).isEqualTo(1);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows()).extracting(row -> row.sourceFile())
                .containsExactly("多工作表.xlsx / 一月", "多工作表.xlsx / 二月");
        assertThat(result.rows()).extracting(row -> row.revenue().intValue())
                .containsExactly(1000, 2200);
    }

    @Test
    void skipsNamedSummarySheetToAvoidDoubleCountingDetailRows() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "明细与汇总.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookWithSummary());

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel});

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).revenue()).isEqualByComparingTo("1000");
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("避免明细与汇总重复计数")
                && value.contains("经营汇总"));
    }

    @Test
    void streamsCsvWithoutCallingReadAllBytes() {
        byte[] csv = ("日期,产品,客户,收入,成本,费用\n"
                + "2026-04-01,产品C,客户丙,3200,1800,400\n").getBytes(StandardCharsets.UTF_8);
        MockMultipartFile csvFile = new MockMultipartFile("files", "stream.csv", "text/csv", csv) {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(csv) {
                    @Override
                    public byte[] readAllBytes() {
                        throw new AssertionError("large CSV must never be materialized with readAllBytes()");
                    }
                };
            }
        };

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{csvFile});

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).revenue()).isEqualByComparingTo("3200");
    }

    @Test
    void detectsGb18030CsvDuringStreamingValidation() {
        String csv = "日期,产品,客户,收入,成本,费用\n2026-04-01,中文产品,中文客户,100,60,10\n";
        MockMultipartFile csvFile = new MockMultipartFile("files", "gb.csv", "text/csv",
                csv.getBytes(java.nio.charset.Charset.forName("GB18030")));

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{csvFile});

        assertThat(result.rows().get(0).product()).isEqualTo("中文产品");
    }

    @Test
    void enforcesLogicalFiveHundredMegabyteLimitBeforeOpeningFile() {
        MockMultipartFile oversized = fakeSizedFile("too-large.csv",
                SpreadsheetImportService.MAX_UPLOAD_BYTES + 1);

        assertThatThrownBy(() -> importer.importFiles(new MockMultipartFile[]{oversized}))
                .isInstanceOf(SpreadsheetImportService.UploadLimitExceededException.class)
                .hasMessageContaining("超过 500MB")
                .hasMessageContaining("too-large.csv");

        MockMultipartFile first = fakeSizedFile("first.csv", 300L * 1024 * 1024);
        MockMultipartFile second = fakeSizedFile("second.csv", 201L * 1024 * 1024);
        assertThatThrownBy(() -> importer.importFiles(new MockMultipartFile[]{first, second}))
                .isInstanceOf(SpreadsheetImportService.UploadLimitExceededException.class)
                .hasMessageContaining("总计超过 500MB");
    }

    @Test
    void rejectsVeryLargeLegacyXlsWithConversionGuidance() {
        MockMultipartFile legacy = fakeSizedFile("legacy.xls",
                SpreadsheetImportService.MAX_LEGACY_XLS_BYTES + 1);

        assertThatThrownBy(() -> importer.importFiles(new MockMultipartFile[]{legacy}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("旧版 .xls")
                .hasMessageContaining("超过 64MB")
                .hasMessageContaining(".xlsx 或 .csv");
    }

    @Test
    void stopsAtConfiguredParsedRowLimitWithActionableMessage() {
        SpreadsheetImportService limitedImporter = new SpreadsheetImportService(1);
        String csv = "日期,产品,客户,收入,成本,费用\n"
                + "2026-01-01,A,C1,100,50,10\n"
                + "2026-01-02,B,C2,200,80,20\n";
        MockMultipartFile file = new MockMultipartFile("files", "rows.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> limitedImporter.importFiles(new MockMultipartFile[]{file}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全上限 1 行")
                .hasMessageContaining("DATAMASTER_IMPORT_MAX_ROWS");
    }

    @Test
    void streamingXlsxKeepsFormattedExcelDatesUsable() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "日期格式.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookWithNumericDate());

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel});

        assertThat(result.rows().get(0).date()).isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    void streamingXlsxPreservesMetricPrecisionInsteadOfDisplayedRounding() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "数值精度.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookWithPreciseMetrics());

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel});

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).measure("convertedQuantity"))
                .isEqualByComparingTo("0.123456789012345");
        assertThat(result.rows().get(1).measure("convertedQuantity"))
                .isEqualByComparingTo("0.24691357802469");
        assertThat(result.rows().get(0).customer()).isEqualTo("000123");
        assertThat(result.quality().invalidNumericCells()).isZero();
    }

    @Test
    void standardWorkbookPathPreservesNumericPrecisionAndFormulaCache() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "旧版数值精度.xls",
                "application/vnd.ms-excel", legacyWorkbookWithPreciseMetrics());

        SpreadsheetImportService.ImportResult result = importer.importFiles(new MockMultipartFile[]{excel});

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.measure("convertedQuantity")).isEqualByComparingTo("123.4567890123");
            assertThat(row.revenue()).isEqualByComparingTo("246.9135780246");
        });
        assertThat(result.quality().invalidNumericCells()).isZero();
    }

    @Test
    void profilesWideSalesReportWithoutGuessingAmongCompetingCostAndProfitDefinitions() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "食品销售宽表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", wideSalesWorkbook());

        SpreadsheetImportService.ImportResult imported = importer.importFiles(new MockMultipartFile[]{excel});
        var result = new AnalysisService().analyze(imported);

        assertThat(imported.rows()).hasSize(3);
        assertThat(imported.rows().get(0).product()).isEqualTo("冷冻食品A");
        assertThat(imported.rows().get(0).customer()).isEqualTo("连锁系统甲");
        assertThat(imported.rows().get(0).quantity()).isEqualByComparingTo("10");
        assertThat(result.summary().revenue()).isEqualByComparingTo("0.00");
        assertThat(result.summary().grossProfit()).isEqualByComparingTo("0.00");
        assertThat(result.capabilities().hasRevenue()).isFalse();
        assertThat(result.capabilities().hasCost()).isFalse();
        assertThat(result.capabilities().grossProfitAvailable()).isFalse();
        assertThat(result.profile().mappings()).anyMatch(value -> value.selected()
                && value.fieldId().equals("product") && value.header().equals("物料名称"));
        assertThat(result.profile().mappings()).anyMatch(value -> value.selected()
                && value.fieldId().equals("customer") && value.header().equals("客户系统"));
        assertThat(result.profile().mappings()).anyMatch(value -> value.selected()
                && value.fieldId().equals("quantity") && value.header().equals("出库数量"));
        assertThat(result.profile().mappingIssues()).anyMatch(value -> value.code().equals("AMBIGUOUS_COST")
                && value.severity().equals("BLOCKING"));
        assertThat(result.profile().mappingIssues()).anyMatch(value -> value.code().equals("AMBIGUOUS_REVENUE")
                && value.severity().equals("BLOCKING"));
        assertThat(result.profile().mappingIssues()).anyMatch(value -> value.code().equals("MULTIPLE_PROFIT_CANDIDATES"));
        assertThat(result.dynamicBreakdowns()).extracting(value -> value.dimensionId())
                .contains("region", "department", "salesGroup", "channel", "businessStatus", "customerDetail");
        assertThat(result.profile().columns()).hasSize(WIDE_HEADERS.length);
    }

    @Test
    void confirmedCostUsesOnlyComparableValidRowsInsteadOfTurningFormulaErrorsIntoZero() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "食品销售宽表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", wideSalesWorkbook());

        SpreadsheetImportService.ImportResult imported = importer.importFiles(
                new MockMultipartFile[]{excel}, Map.of("revenue", "Z", "cost", "BQ"));
        var result = new AnalysisService().analyze(imported);

        assertThat(result.capabilities().hasCost()).isTrue();
        assertThat(result.capabilities().grossProfitAvailable()).isTrue();
        assertThat(result.capabilities().hasExpenses()).isFalse();
        assertThat(result.capabilities().operatingProfitAvailable()).isFalse();
        assertThat(result.summary().revenue()).isEqualByComparingTo("1000.00");
        assertThat(result.summary().cost()).isEqualByComparingTo("600.00");
        assertThat(result.summary().grossComparableRevenue()).isEqualByComparingTo("900.00");
        assertThat(result.summary().grossComparableCost()).isEqualByComparingTo("600.00");
        assertThat(result.summary().grossProfit()).isEqualByComparingTo("300.00");
        assertThat(result.summary().grossMargin()).isEqualByComparingTo("0.3333");
        assertThat(result.summary().operatingProfit()).isEqualByComparingTo("0.00");
        assertThat(result.capabilities().grossProfitExcludedRows()).isEqualTo(1);
        assertThat(result.capabilities().grossProfitExcludedRevenue()).isEqualByComparingTo("100.00");
        assertThat(result.capabilities().grossProfitRevenueCoverage()).isEqualByComparingTo("0.9000");
        assertThat(result.quality().invalidNumericCells()).isEqualTo(1);
        assertThat(result.quality().profiledNumericErrors()).isGreaterThan(1);
        assertThat(result.quality().warnings()).anyMatch(value -> value.contains("公式结果无法解析"));
    }

    @Test
    void profilesUnknownHeadersAndCanReimportWithExplicitSemanticMappings() {
        String csv = "发生日,货品描述,往来方,业务规模,资源耗用\n"
                + "2026-06-01,新品A,客户甲,1200,700\n"
                + "2026-06-02,新品B,客户乙,800,500\n";
        MockMultipartFile firstUpload = new MockMultipartFile("files", "陌生系统导出.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        SpreadsheetImportService.ImportResult profiled = importer.importFiles(
                new MockMultipartFile[]{firstUpload});
        var initial = new AnalysisService().analyze(profiled);

        assertThat(profiled.rows()).hasSize(2);
        assertThat(profiled.profile().columns()).hasSize(5);
        assertThat(profiled.profile().mappings()).isEmpty();
        assertThat(initial.capabilities().hasRevenue()).isFalse();
        assertThat(initial.summary().revenue()).isEqualByComparingTo("0.00");
        assertThat(profiled.quality().warnings()).anyMatch(value -> value.contains("仅生成结构画像"));

        MockMultipartFile confirmedUpload = new MockMultipartFile("files", "陌生系统导出.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        SpreadsheetImportService.ImportResult confirmed = importer.importFiles(
                new MockMultipartFile[]{confirmedUpload}, Map.of(
                        "date", "发生日",
                        "product", "货品描述",
                        "customer", "往来方",
                        "revenue", "业务规模",
                        "cost", "资源耗用"));
        var result = new AnalysisService().analyze(confirmed);

        assertThat(result.capabilities().hasRevenue()).isTrue();
        assertThat(result.capabilities().hasCost()).isTrue();
        assertThat(result.capabilities().grossProfitAvailable()).isTrue();
        assertThat(result.summary().revenue()).isEqualByComparingTo("2000.00");
        assertThat(result.summary().cost()).isEqualByComparingTo("1200.00");
        assertThat(result.summary().grossProfit()).isEqualByComparingTo("800.00");
        assertThat(result.profile().mappings()).anyMatch(value -> value.selected()
                && value.fieldId().equals("revenue") && value.header().equals("业务规模"));
    }

    @Test
    void unselectedFormulaErrorsStayOutOfSelectedInvalidNumericCount() throws Exception {
        MockMultipartFile excel = new MockMultipartFile("files", "食品销售宽表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", wideSalesWorkbook());

        SpreadsheetImportService.ImportResult imported = importer.importFiles(
                new MockMultipartFile[]{excel}, Map.of("revenue", "Z", "cost", "AG"));

        assertThat(imported.quality().invalidNumericCells()).isZero();
        assertThat(imported.quality().profiledNumericErrors()).isGreaterThanOrEqualTo(3);
        assertThat(imported.quality().warnings()).anyMatch(value -> value.contains("候选指标列"));
    }

    @Test
    void zeroRevenuePositiveCostGroupStillExposesGrossLoss() {
        String csv = "日期,产品,客户,收入,成本\n"
                + "2026-06-01,赠品,客户甲,0,100\n"
                + "2026-06-02,正常品,客户乙,1000,600\n";
        MockMultipartFile file = new MockMultipartFile("files", "赠品与销售.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        var result = new AnalysisService().analyze(importer.importFiles(new MockMultipartFile[]{file}));
        var freebie = result.products().stream().filter(value -> value.name().equals("赠品"))
                .findFirst().orElseThrow();

        assertThat(freebie.grossProfitAvailable()).isTrue();
        assertThat(freebie.revenue()).isEqualByComparingTo("0.00");
        assertThat(freebie.cost()).isEqualByComparingTo("100.00");
        assertThat(freebie.grossProfit()).isEqualByComparingTo("-100.00");
        assertThat(freebie.grossMargin()).isNull();
        assertThat(result.summary().grossProfit()).isEqualByComparingTo("300.00");
    }

    @Test
    void salesOnlyReportNeverPresentsRevenueAsOneHundredPercentGrossProfit() {
        String csv = "业务日期,物料名称,客户系统,出库数量,销售额,销售区域,渠道\n"
                + "2026-06-01,A,客户甲,5,500,华东,直营\n"
                + "2026-06-02,B,客户乙,8,800,华南,经销\n";
        MockMultipartFile file = new MockMultipartFile("files", "销售明细.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        var result = new AnalysisService().analyze(importer.importFiles(new MockMultipartFile[]{file}));

        assertThat(result.summary().revenue()).isEqualByComparingTo("1300.00");
        assertThat(result.summary().grossProfit()).isEqualByComparingTo("0.00");
        assertThat(result.summary().grossMargin()).isEqualByComparingTo("0.0000");
        assertThat(result.capabilities().grossProfitAvailable()).isFalse();
        assertThat(result.capabilities().operatingProfitAvailable()).isFalse();
        assertThat(result.insights()).allMatch(value -> !value.finding().contains("100.0%"));
    }

    @Test
    void sourceQualifiedOverrideReusesRecognisedColumnAcrossSameStructureFiles() {
        String first = "日期,产品,客户,备注,销售额\n2026-01-01,A,甲,一月,100\n";
        String second = "日期,产品,客户,备注,销售额\n2026-02-01,B,乙,二月,200\n";
        MockMultipartFile one = new MockMultipartFile("files", "one.csv", "text/csv",
                first.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile two = new MockMultipartFile("files", "two.csv", "text/csv",
                second.getBytes(StandardCharsets.UTF_8));

        var result = new AnalysisService().analyze(importer.importFiles(
                new MockMultipartFile[]{one, two}, Map.of("revenue", "one.csv::E")));

        assertThat(result.capabilities().hasRevenue()).isTrue();
        assertThat(result.summary().revenue()).isEqualByComparingTo("300.00");
        assertThat(result.profile().mappingIssues())
                .noneMatch(value -> value.code().startsWith("MAPPING_OVERRIDE_NOT_FOUND"));
    }

    @Test
    void failedCriticalOverrideInAnySourceDisablesPartialRevenueResult() {
        String first = "日期,产品,客户,备注,销售额\n2026-01-01,A,甲,一月,100\n";
        String shifted = "日期,产品,客户,备注,说明,销售额\n2026-02-01,B,乙,二月,移动列,200\n";
        MockMultipartFile one = new MockMultipartFile("files", "one.csv", "text/csv",
                first.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile two = new MockMultipartFile("files", "two.csv", "text/csv",
                shifted.getBytes(StandardCharsets.UTF_8));

        var result = new AnalysisService().analyze(importer.importFiles(
                new MockMultipartFile[]{one, two}, Map.of("revenue", "one.csv::E")));

        assertThat(result.profile().mappingIssues()).anyMatch(value ->
                value.code().equals("MAPPING_OVERRIDE_NOT_FOUND_REVENUE")
                        && value.severity().equals("BLOCKING"));
        assertThat(result.capabilities().hasRevenue()).isFalse();
        assertThat(result.summary().revenue()).isEqualByComparingTo("0.00");
        assertThat(result.capabilities().unavailableReasons())
                .anyMatch(value -> value.contains("至少一份数据源") && value.contains("收入口径"));
    }

    @Test
    void percentFormattedMetricCandidatesAreProfiledAsNumbers() {
        String csv = "日期,产品,客户,销售额,毛利率%\n2026-06-01,A,甲,1000,12.5%\n";
        MockMultipartFile file = new MockMultipartFile("files", "percent.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        var imported = importer.importFiles(new MockMultipartFile[]{file});
        var margin = imported.profile().mappings().stream()
                .filter(value -> value.fieldId().equals("reportedMargin"))
                .findFirst().orElseThrow();
        var column = imported.profile().columns().stream()
                .filter(value -> value.header().equals("毛利率%"))
                .findFirst().orElseThrow();

        assertThat(margin.validCount()).isEqualTo(1);
        assertThat(column.minimum()).isEqualByComparingTo("0.125");
        assertThat(imported.quality().profiledNumericErrors()).isZero();
    }

    private static final String[] WIDE_HEADERS = {
            "业务日期", "销售区域", "销售部门", "销售组", "业务状态", "产品类别", "产品形态",
            "一级分类", "二级分类", "三级分类", "四级分类", "五级分类", "六级分类", "渠道",
            "客户系统", "送货客户", "送货地址", "物料编码", "物料名称", "规格型号", "计量单位",
            "重量", "换算只数", "出库数量", "单价", "金额", "含税单价", "税额", "价税合计",
            "调拨单价(只)", "调拨单价(斤)", "运费单只成本", "门店调拨成本", "门店单利",
            "门店毛利", "门店毛利率%", "分公司只调拨价", "分公司斤调拨价", "运费单只成本",
            "分公司调拨成本", "分公司单利", "分公司毛利", "分公司毛利率", "分公司+门店毛利",
            "分公司总毛利率", "单位生产成本", "生产成本", "生产单利", "生产毛利", "生产毛利率",
            "总毛利", "备注", "换算系数", "是否库存特价产品", "收入费用归集", "客户分析分类",
            "大类", "经营分析分类", "经营分析大类", "产品形态（汇报）", "仓库", "库存组织", "等级",
            "调整", "折扣", "单只运费", "运费成本", "价税合计（调后）", "调拨成本（含运费）",
            "生产成本（含运费）", "减运费后毛利", "后台单只毛利", "分摊后台毛利", "渠道",
            "销售组&客户系统", "渠道分类"
    };

    private static byte[] workbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet empty = workbook.createSheet("说明");
            // The first sheet is intentionally empty; the importer must select the first non-empty sheet.
            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            String[] headers = {"日期", "产品", "客户", "数量", "收入", "成本", "费用"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("2026-01-01");
            row.createCell(1).setCellValue("产品A");
            row.createCell(2).setCellValue("客户甲");
            row.createCell(3).setCellValue(2);
            row.createCell(4).setCellValue(1000);
            row.createCell(5).setCellValue(600);
            row.createCell(6).setCellValue(200);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] multiSheetWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet january = workbook.createSheet("一月");
            writeSheet(january, new String[]{"日期", "产品", "客户", "收入", "成本", "费用"},
                    new Object[]{"2026-01-01", "产品A", "客户甲", 1000d, 600d, 100d});
            Sheet note = workbook.createSheet("说明");
            // Empty documentation sheet must not stop subsequent data sheets from being parsed.
            Sheet february = workbook.createSheet("二月");
            writeSheet(february, new String[]{"订单日期", "商品名称", "客户名称", "销售额", "销售成本", "期间费用"},
                    new Object[]{"2026-02-01", "产品B", "客户乙", 2200d, 1200d, 300d});
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] workbookWithSummary() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet detail = workbook.createSheet("经营明细");
            writeSheet(detail, new String[]{"日期", "产品", "客户", "收入", "成本", "费用"},
                    new Object[]{"2026-01-01", "产品A", "客户甲", 1000d, 600d, 100d});
            Sheet summary = workbook.createSheet("经营汇总");
            writeSheet(summary, new String[]{"产品", "收入", "成本", "费用"},
                    new Object[]{"产品A", 1000d, 600d, 100d});
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] workbookWithNumericDate() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            writeSheet(sheet, new String[]{"日期", "产品", "客户", "收入", "成本", "费用"},
                    new Object[]{"", "产品A", "客户甲", 1000d, 600d, 100d});
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("m/d/yy"));
            sheet.getRow(1).getCell(0).setCellValue(LocalDate.of(2026, 5, 6));
            sheet.getRow(1).getCell(0).setCellStyle(dateStyle);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] workbookWithPreciseMetrics() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            String[] headers = {"日期", "产品", "客户", "换算只数", "销售额"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

            CellStyle twoDecimals = workbook.createCellStyle();
            twoDecimals.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            CellStyle identifier = workbook.createCellStyle();
            identifier.setDataFormat(workbook.createDataFormat().getFormat("000000"));

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("2026-06-01");
            first.createCell(1).setCellValue("产品A");
            first.createCell(2).setCellValue(123);
            first.getCell(2).setCellStyle(identifier);
            first.createCell(3).setCellValue(0.123456789012345d);
            first.getCell(3).setCellStyle(twoDecimals);
            first.createCell(4).setCellValue(100);

            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("2026-06-02");
            second.createCell(1).setCellValue("产品B");
            second.createCell(2).setCellValue("客户乙");
            second.createCell(3).setCellFormula("D2*2");
            second.getCell(3).setCellValue(0.24691357802469d);
            second.getCell(3).setCellStyle(twoDecimals);
            second.createCell(4).setCellValue(200);

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] legacyWorkbookWithPreciseMetrics() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            String[] headers = {"日期", "产品", "客户", "换算只数", "销售额"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            CellStyle twoDecimals = workbook.createCellStyle();
            twoDecimals.setDataFormat(workbook.createDataFormat().getFormat("0.00"));

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("2026-06-01");
            row.createCell(1).setCellValue("产品A");
            row.createCell(2).setCellValue("客户甲");
            row.createCell(3).setCellValue(123.4567890123d);
            row.getCell(3).setCellStyle(twoDecimals);
            row.createCell(4).setCellFormula("D2*2");
            row.getCell(4).setCellValue(246.9135780246d);
            row.getCell(4).setCellStyle(twoDecimals);

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] wideSalesWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("食品事业部汇总6月");
            Row header = sheet.createRow(0);
            for (int index = 0; index < WIDE_HEADERS.length; index++) {
                header.createCell(index).setCellValue(WIDE_HEADERS[index]);
            }
            writeWideSalesRow(sheet.createRow(1), "2026-06-01", "华东", "一部", "A组", "正常",
                    "连锁系统甲", "门店甲", "冷冻食品A", 10, 400, 360, 250d, 110d);
            writeWideSalesRow(sheet.createRow(2), "2026-06-02", "华南", "二部", "B组", "正常",
                    "连锁系统乙", "门店乙", "冷冻食品B", 20, 500, 450, 350d, 100d);
            writeWideSalesRow(sheet.createRow(3), "2026-06-03", "华北", "一部", "A组", "退货复核",
                    "连锁系统丙", "门店丙", "冷冻食品A", 5, 100, 90, null, null);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void writeWideSalesRow(Row row, String date, String region, String department,
                                          String salesGroup, String status, String customerSystem,
                                          String deliveryCustomer, String product, double quantity,
                                          double amount, double taxInclusive, Double transferCost,
                                          Double reportedProfit) {
        row.createCell(0).setCellValue(date);                    // A 业务日期
        row.createCell(1).setCellValue(region);                  // B 销售区域
        row.createCell(2).setCellValue(department);              // C 销售部门
        row.createCell(3).setCellValue(salesGroup);              // D 销售组
        row.createCell(4).setCellValue(status);                  // E 业务状态
        row.createCell(5).setCellValue("速冻食品");               // F 产品类别
        row.createCell(13).setCellValue("直营");                 // N 渠道
        row.createCell(14).setCellValue(customerSystem);         // O 客户系统
        row.createCell(15).setCellValue(deliveryCustomer);       // P 送货客户
        row.createCell(18).setCellValue(product);                // S 物料名称
        row.createCell(23).setCellValue(quantity);               // X 出库数量
        row.createCell(25).setCellValue(amount);                 // Z 金额（默认收入）
        row.createCell(28).setCellValue(taxInclusive);           // AC 价税合计（候选收入）
        row.createCell(32).setCellValue(80);                     // AG 门店调拨成本（候选成本）
        row.createCell(42).setCellValue(20);                     // AQ 分公司+门店毛利（候选利润）
        row.createCell(50).setCellValue(30);                     // AY 总毛利（候选利润）
        row.createCell(67).setCellValue(taxInclusive);           // BP 调后价税（候选收入）
        if (transferCost == null) row.createCell(68).setCellValue("#N/A");
        else row.createCell(68).setCellValue(transferCost);      // BQ 调拨成本含运费
        if (transferCost == null) row.createCell(69).setCellValue("#DIV/0!");
        else row.createCell(69).setCellValue(transferCost + 10); // BR
        if (reportedProfit == null) row.createCell(70).setCellValue("#N/A");
        else row.createCell(70).setCellValue(reportedProfit);    // BS 减运费后毛利
        row.createCell(73).setCellValue("直营");                 // BV 重复渠道
        row.createCell(75).setCellValue("直营");                 // BX 渠道分类
    }

    private static MockMultipartFile fakeSizedFile(String filename, long size) {
        return new MockMultipartFile("files", filename, "text/csv", new byte[]{'x'}) {
            @Override
            public long getSize() {
                return size;
            }
        };
    }

    private static void writeSheet(Sheet sheet, String[] headers, Object[] values) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
        Row row = sheet.createRow(1);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number number) row.createCell(i).setCellValue(number.doubleValue());
            else row.createCell(i).setCellValue(String.valueOf(values[i]));
        }
    }
}
