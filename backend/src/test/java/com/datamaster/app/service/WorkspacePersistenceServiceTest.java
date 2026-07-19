package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisResult;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.Insight;
import com.datamaster.app.domain.InsightSource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePersistenceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void restoresTheLastWorkspaceAcrossServiceRestartAndNeverClearsItOnStartup() {
        Path workspace = tempDir.resolve("workspace");
        AnalysisService firstAnalysis = new AnalysisService();
        SpreadsheetImportService importer = new SpreadsheetImportService(1_000_000);
        ObjectMapper mapper = new ObjectMapper();
        WorkspacePersistenceService first = new WorkspacePersistenceService(
                workspace, importer, firstAnalysis, mapper);
        MockMultipartFile january = csv("一月.csv", "2026-01-01,产品A,客户甲,1000,600\n");
        MockMultipartFile february = csv("二月.csv", "2026-02-01,产品B,客户乙,500,300\n");
        var result = firstAnalysis.analyze(importer.importFiles(new MockMultipartFile[]{january, february}));
        first.save(firstAnalysis.requireSession(result.id()), new MockMultipartFile[]{january, february}, Map.of());

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(
                workspace, importer, restartedAnalysis, mapper);
        var restored = restarted.current();

        assertThat(restored.analysis()).isNotNull();
        assertThat(restored.analysis().id()).isEqualTo(result.id());
        assertThat(restored.analysis().rowCount()).isEqualTo(2);
        assertThat(restored.analysis().summary().revenue()).isEqualByComparingTo("1500.00");
        assertThat(restored.sources()).extracting(source -> source.name())
                .containsExactly("一月.csv", "二月.csv");
        assertThat(restartedAnalysis.requireSession(result.id()).rows()).hasSize(2);
        assertThat(workspace.resolve("current/analysis-session.json.gz")).exists();
    }

    @Test
    void restoresARealVersionOneManifestThatDoesNotContainRelationshipFields() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("2026年6月销售数据汇总.csv", "2026-06-01,产品A,客户甲,1000,600\n")}, Map.of());
        Path current = tempDir.resolve("workspace/current");
        Path stored;
        try (var files = Files.list(current.resolve("sources"))) {
            stored = files.findFirst().orElseThrow();
        }
        String id = stored.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Files.writeString(current.resolve("manifest.json"), """
                {"version":1,"savedAt":"2026-07-14T15:34:29.289996Z","mapping":{},"sources":[
                  {"id":"%s","name":"2026年6月销售数据汇总.csv","storedFile":"%s","size":%d,"contentType":"text/csv"}
                ]}
                """.formatted(id, stored.getFileName(), Files.size(stored)));

        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis()).isNotNull();
        assertThat(restored.analysis().rowCount()).isEqualTo(1);
        assertThat(restored.analysis().summary().revenue()).isEqualByComparingTo("1000.00");
        assertThat(restored.sources()).singleElement().satisfies(source -> {
            assertThat(source.name()).isEqualTo("2026年6月销售数据汇总.csv");
            assertThat(source.seriesKey()).isEqualTo("销售数据汇总");
        });
    }

    @Test
    void explicitClearDeletesWorkspaceAndNothingElseDoes() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("一月.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        String deletedAnalysisId = fixture.workspace().current().analysis().id();

        fixture.workspace().clear();

        assertThat(fixture.workspace().current().analysis()).isNull();
        assertThat(tempDir.resolve("workspace/current")).doesNotExist();
        assertThatThrownBy(() -> fixture.analysis().requireSession(deletedAnalysisId))
                .isInstanceOf(AnalysisService.AnalysisNotFoundException.class);
    }

    @Test
    void restoresBackupLeftByAnInterruptedAtomicReplacement() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("保留.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        Path root = tempDir.resolve("workspace");
        Files.move(root.resolve("current"), root.resolve(".backup-interrupted"));

        WorkspacePersistenceService restarted = new WorkspacePersistenceService(root,
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis()).isNotNull();
        assertThat(restored.analysis().summary().revenue()).isEqualByComparingTo("1000.00");
        assertThat(restored.sources()).singleElement().extracting(source -> source.name()).isEqualTo("保留.csv");
        assertThat(root.resolve("current/manifest.json")).exists();
    }

    @Test
    void clearWhileCurrentMissingRemovesInterruptedBackup() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("保留.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        Path root = tempDir.resolve("workspace");
        Files.move(root.resolve("current"), root.resolve(".backup-interrupted"));

        fixture.workspace().clear();

        WorkspacePersistenceService restarted = new WorkspacePersistenceService(root,
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());
        assertThat(restarted.current().analysis()).isNull();
        assertThat(root.resolve(".backup-interrupted")).doesNotExist();
    }

    @Test
    void clearRemovesStaleBackupsAndDoesNotResurrectDeletedWorkspace() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("保留.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        Path root = tempDir.resolve("workspace");
        copyDirectory(root.resolve("current"), root.resolve(".backup-stale"));
        copyDirectory(root.resolve("current"), root.resolve(".deleted-stale"));

        fixture.workspace().clear();

        WorkspacePersistenceService restarted = new WorkspacePersistenceService(root,
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());
        assertThat(restarted.current().analysis()).isNull();
        assertThat(root.resolve("current")).doesNotExist();
        assertThat(root.resolve(".backup-stale")).doesNotExist();
        assertThat(root.resolve(".deleted-stale")).doesNotExist();
    }

    @Test
    void invalidBackupsProduceRestoreErrorInsteadOfAnEmptyWorkspace() throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve(".backup-corrupt"));
        Files.writeString(root.resolve(".backup-corrupt/manifest.json"), "not-json");
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(root,
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());

        assertThatThrownBy(restarted::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("恢复点");
    }

    @Test
    void deletesOnePersistedSourceAndRecalculatesFromTheRemainingFile() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("一月.csv", "2026-01-01,产品A,客户甲,1000,600\n"),
                csv("二月.csv", "2026-02-01,产品B,客户乙,500,300\n")}, Map.of());
        var current = fixture.workspace().current();
        String januaryId = current.sources().stream().filter(source -> source.name().equals("一月.csv"))
                .findFirst().orElseThrow().id();

        var updated = fixture.workspace().deleteSource(januaryId);

        assertThat(updated.sources()).singleElement().extracting(source -> source.name()).isEqualTo("二月.csv");
        assertThat(updated.analysis().sourceFileCount()).isEqualTo(1);
        assertThat(updated.analysis().rowCount()).isEqualTo(1);
        assertThat(updated.analysis().summary().revenue()).isEqualByComparingTo("500.00");
        assertThat(fixture.workspace().current().analysis().id()).isEqualTo(updated.analysis().id());
        assertThat(fixture.analysis().requireSession(current.analysis().id()).result().id())
                .isEqualTo(current.analysis().id());
    }

    @Test
    void appendsMonthlySourcesWithoutReplacingExistingDataAndRetainsLineageAcrossRestart() {
        MockMultipartFile january = csv("2026年1月销售数据汇总.csv",
                "2026-01-05,产品A,客户甲,1000,600\n");
        Fixture fixture = fixture(new MockMultipartFile[]{january}, Map.of());
        String originalId = fixture.workspace().current().analysis().id();
        MockMultipartFile february = csv("2026年2月销售数据汇总.csv",
                "2026-02-06,产品B,客户乙,500,300\n");
        MockMultipartFile march = csv("2026年3月销售数据汇总.csv",
                "2026-03-07,产品C,客户丙,250,100\n");

        var appended = fixture.workspace().append(new MockMultipartFile[]{february, march}, Map.of());

        assertThat(appended.analysis().id()).isNotEqualTo(originalId);
        assertThat(appended.analysis().sourceFileCount()).isEqualTo(3);
        assertThat(appended.analysis().rowCount()).isEqualTo(3);
        assertThat(appended.analysis().summary().revenue()).isEqualByComparingTo("1750.00");
        assertThat(appended.sources()).extracting(source -> source.name()).containsExactly(
                "2026年1月销售数据汇总.csv", "2026年2月销售数据汇总.csv", "2026年3月销售数据汇总.csv");
        assertThat(appended.sources()).allSatisfy(source -> {
            assertThat(source.sha256()).hasSize(64);
            assertThat(source.periodBasis()).isEqualTo("DATA_ROWS");
            assertThat(source.seriesKey()).isEqualTo("销售数据汇总");
            assertThat(source.needsAiReview()).isFalse();
            assertThat(source.periods()).hasSize(1);
        });
        assertThat(appended.sources()).flatExtracting(source -> source.periods())
                .containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(fixture.analysis().requireSession(originalId).result().id()).isEqualTo(originalId);

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), restartedAnalysis, new ObjectMapper());
        var restored = restarted.current();
        assertThat(restored.analysis().summary().revenue()).isEqualByComparingTo("1750.00");
        assertThat(restored.sources()).hasSize(3);
        assertThat(restored.sources()).flatExtracting(source -> source.periods())
                .containsExactly("2026-01", "2026-02", "2026-03");
    }

    @Test
    void skipsExactContentDuplicatesAcrossExistingWorkspaceAndOnePickerBatch() {
        String rows = "2026-01-05,产品A,客户甲,1000,600\n";
        Fixture fixture = fixture(new MockMultipartFile[]{csv("原始.csv", rows)}, Map.of());
        var before = fixture.workspace().current();
        MockMultipartFile renamedDuplicate = csv("重命名.csv", rows);
        MockMultipartFile april = csv("2026年4月销售数据汇总.csv",
                "2026-04-01,产品B,客户乙,500,200\n");
        MockMultipartFile aprilAgain = csv("四月副本.csv",
                "2026-04-01,产品B,客户乙,500,200\n");

        var appended = fixture.workspace().append(
                new MockMultipartFile[]{renamedDuplicate, april, aprilAgain}, Map.of());

        assertThat(appended.sources()).hasSize(2);
        assertThat(appended.sources()).extracting(source -> source.name())
                .containsExactly("原始.csv", "2026年4月销售数据汇总.csv");
        assertThat(appended.analysis().rowCount()).isEqualTo(2);
        assertThat(appended.analysis().summary().revenue()).isEqualByComparingTo("1500.00");
        assertThat(appended.analysis().id()).isNotEqualTo(before.analysis().id());

        var duplicateOnly = fixture.workspace().append(new MockMultipartFile[]{renamedDuplicate}, Map.of());
        assertThat(duplicateOnly.analysis().id()).isEqualTo(appended.analysis().id());
        assertThat(duplicateOnly.sources()).hasSize(2);
    }

    @Test
    void marksDifferentVersionsOfTheSameMonthlySeriesForHumanOrAiReview() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("2026年4月销售数据汇总.csv", "2026-04-01,产品A,客户甲,1000,600\n")}, Map.of());

        var appended = fixture.workspace().append(new MockMultipartFile[]{
                csv("销售数据汇总-2026-04.csv", "2026-04-02,产品B,客户乙,500,200\n")}, Map.of());

        assertThat(appended.sources()).hasSize(2).allSatisfy(source -> {
            assertThat(source.periods()).containsExactly("2026-04");
            assertThat(source.needsAiReview()).isTrue();
            assertThat(source.relationshipNote()).contains("同月份", "可能重复累计", "AI");
        });
    }

    @Test
    void failedAppendLeavesExistingSourcesAndAnalysisUntouched() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("保留.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        var before = fixture.workspace().current();
        MockMultipartFile broken = new MockMultipartFile("files", "损坏.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not-an-xlsx".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fixture.workspace().append(new MockMultipartFile[]{broken}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        var after = fixture.workspace().current();
        assertThat(after.analysis().id()).isEqualTo(before.analysis().id());
        assertThat(after.analysis().summary().revenue()).isEqualByComparingTo("1000.00");
        assertThat(after.sources()).extracting(source -> source.name()).containsExactly("保留.csv");
    }

    @Test
    void flagsFilenameAndRowPeriodMismatchAsAiReviewCandidateWithoutChangingRows() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("2026年5月销售数据汇总.csv", "2026-04-30,产品A,客户甲,1000,600\n")}, Map.of());

        var source = fixture.workspace().current().sources().get(0);

        assertThat(source.periods()).containsExactly("2026-04");
        assertThat(source.periodBasis()).isEqualTo("DATA_ROWS");
        assertThat(source.needsAiReview()).isTrue();
        assertThat(source.relationshipNote()).contains("2026-05", "2026-04", "AI");
        assertThat(fixture.workspace().current().analysis().monthly()).singleElement()
                .extracting(value -> value.month()).isEqualTo("2026-04");
    }

    @Test
    void derivesXlsxPeriodFromWorksheetRowsInsteadOfFallingBackToFilename() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                xlsx("2026年3月销售数据汇总.xlsx", "2026-03-18", "1200", "700")}, Map.of());

        var source = fixture.workspace().current().sources().get(0);

        assertThat(fixture.workspace().current().analysis().rowCount()).isEqualTo(1);
        assertThat(source.periods()).containsExactly("2026-03");
        assertThat(source.periodBasis()).isEqualTo("DATA_ROWS");
        assertThat(source.needsAiReview()).isFalse();
        assertThat(source.relationshipNote()).contains("2026-03", "数据行日期");
    }

    @Test
    void deletingTheOnlyPersistedSourceClearsWorkspaceAndReleasesItsSession() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("唯一.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        var current = fixture.workspace().current();

        var updated = fixture.workspace().deleteSource(current.sources().get(0).id());

        assertThat(updated.analysis()).isNull();
        assertThat(updated.sources()).isEmpty();
        assertThat(tempDir.resolve("workspace/current")).doesNotExist();
        assertThatThrownBy(() -> fixture.analysis().requireSession(current.analysis().id()))
                .isInstanceOf(AnalysisService.AnalysisNotFoundException.class);
    }

    @Test
    void corruptSnapshotFallsBackToSourceReimportWithPersistedMappings() throws Exception {
        Map<String, String> mapping = Map.of("date", "发生日", "product", "货品描述",
                "customer", "往来方", "revenue", "业务规模", "cost", "资源耗用");
        String content = "发生日,货品描述,往来方,业务规模,资源耗用\n"
                + "2026-06-01,新品A,客户甲,1200,700\n";
        MockMultipartFile file = new MockMultipartFile("files", "陌生系统.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
        Fixture fixture = fixture(new MockMultipartFile[]{file}, mapping);
        String oldId = fixture.workspace().current().analysis().id();
        Files.writeString(tempDir.resolve("workspace/current/analysis-session.json.gz"), "corrupt");

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), restartedAnalysis, new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis().id()).isNotEqualTo(oldId);
        assertThat(restored.analysis().summary().revenue()).isEqualByComparingTo("1200.00");
        assertThat(restored.mapping()).containsAllEntriesOf(mapping);
        byte[] header = Files.readAllBytes(tempDir.resolve("workspace/current/analysis-session.json.gz"));
        assertThat(header[0]).isEqualTo((byte) 0x1f);
        assertThat(header[1]).isEqualTo((byte) 0x8b);
    }

    @Test
    void legacySnapshotWithoutComparableBasisFieldsIsRebuiltFromPersistedSources() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("旧版快照.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        AnalysisSession current = fixture.analysis().requireSession(fixture.workspace().current().analysis().id());
        FinancialSummary value = current.result().summary();
        FinancialSummary legacy = new FinancialSummary(value.revenue(), value.cost(),
                null, null, value.grossProfit(), value.expenses(),
                null, null, null, value.operatingProfit(), value.grossMargin(), value.operatingMargin());
        AnalysisResult source = current.result();
        Instant aiGeneratedAt = Instant.parse("2026-07-14T08:30:00Z");
        List<Insight> persistedAiInsights = List.of(new Insight("已保存 AI 结论", "原数据证据", "继续执行", "EV-SUM-GP"));
        AnalysisResult legacyResult = new AnalysisResult(source.id(), source.sourceFileCount(), source.rowCount(),
                legacy, source.quality(), source.products(), source.customers(), source.monthly(), persistedAiInsights,
                InsightSource.AI, "deepseek", "deepseek-v4-flash", aiGeneratedAt,
                source.profile(), source.capabilities(), source.dynamicBreakdowns());
        Path snapshot = tempDir.resolve("workspace/current/analysis-session.json.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
            new ObjectMapper().writeValue(output, new AnalysisSession(legacyResult, current.rows()));
        }

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), restartedAnalysis, new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis().id()).isNotEqualTo(source.id());
        assertThat(restored.analysis().summary().grossComparableRevenue()).isEqualByComparingTo("1000.00");
        assertThat(restored.analysis().summary().grossComparableCost()).isEqualByComparingTo("600.00");
        assertThat(restored.analysis().insightSource()).isEqualTo(InsightSource.AI);
        assertThat(restored.analysis().insightProviderId()).isEqualTo("deepseek");
        assertThat(restored.analysis().insightModel()).isEqualTo("deepseek-v4-flash");
        assertThat(restored.analysis().insightsGeneratedAt()).isEqualTo(aiGeneratedAt);
        assertThat(restored.analysis().insights()).extracting(Insight::title).containsExactly("已保存 AI 结论");
        assertThat(restored.sources()).singleElement().extracting(item -> item.name()).isEqualTo("旧版快照.csv");

        var cachedRead = restarted.current();
        assertThat(cachedRead.analysis().insightSource()).isEqualTo(InsightSource.AI);
        assertThat(cachedRead.analysis().insightProviderId()).isEqualTo("deepseek");
        assertThat(cachedRead.analysis().insights()).extracting(Insight::title)
                .containsExactly("已保存 AI 结论");
    }

    @Test
    void legacyNegativeRevenueMarginIsRebuiltAsNotApplicable() throws Exception {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("负收入快照.csv", "2026-01-01,商业折扣,客户甲,-100,-5\n")}, Map.of());
        AnalysisSession current = fixture.analysis().requireSession(fixture.workspace().current().analysis().id());
        AnalysisResult source = current.result();
        Breakdown actual = source.products().get(0);
        Breakdown legacyProduct = new Breakdown(actual.name(), actual.revenue(), actual.cost(),
                actual.grossProfit(), actual.expenses(), actual.operatingProfit(), new BigDecimal("0.9500"),
                actual.operatingMargin(), actual.quantity(), actual.grossProfitAvailable(),
                actual.operatingProfitAvailable(), actual.grossProfitCoverage(), actual.operatingProfitCoverage(),
                actual.grossProfitExcludedRows(), actual.operatingProfitExcludedRows());
        AnalysisResult legacyResult = new AnalysisResult(source.id(), source.sourceFileCount(), source.rowCount(),
                source.summary(), source.quality(), List.of(legacyProduct), source.customers(), source.monthly(),
                source.insights(), source.insightSource(), source.insightProviderId(), source.insightModel(),
                source.insightsGeneratedAt(), source.profile(), source.capabilities(), source.dynamicBreakdowns());
        Path snapshot = tempDir.resolve("workspace/current/analysis-session.json.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
            new ObjectMapper().writeValue(output, new AnalysisSession(legacyResult, current.rows()));
        }

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), restartedAnalysis, new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis().id()).isNotEqualTo(source.id());
        assertThat(restored.analysis().products()).singleElement().satisfies(product -> {
            assertThat(product.grossProfit()).isEqualByComparingTo("-95.00");
            assertThat(product.grossMargin()).isNull();
        });
    }

    @Test
    void failedReplacementLeavesPreviousWorkspaceUntouched() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("可靠.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        String originalId = fixture.workspace().current().analysis().id();
        MockMultipartFile unsupported = new MockMultipartFile("files", "bad.exe",
                "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> fixture.workspace().save(
                fixture.analysis().requireSession(originalId), new MockMultipartFile[]{unsupported}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持保存");

        assertThat(fixture.workspace().current().analysis().id()).isEqualTo(originalId);
        assertThat(fixture.workspace().current().sources()).singleElement()
                .extracting(source -> source.name()).isEqualTo("可靠.csv");
    }

    @Test
    void refreshSnapshotPersistsAiInsightsWithoutReplacingSourcesOrManifest() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("可靠.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        var before = fixture.workspace().current();
        Path sourcePath = tempDir.resolve("workspace/current/sources");
        long sourceCount;
        try (var files = Files.list(sourcePath)) {
            sourceCount = files.count();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        fixture.analysis().updateInsights(before.analysis().id(),
                List.of(new Insight("AI 结论", "聚合事实", "执行动作", "本地证据")),
                InsightSource.AI, "deepseek", "deepseek-v4-flash", Instant.now());
        fixture.workspace().refreshSnapshot(fixture.analysis().requireSession(before.analysis().id()));

        AnalysisService restartedAnalysis = new AnalysisService();
        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), restartedAnalysis, new ObjectMapper());
        var restored = restarted.current();

        assertThat(restored.analysis().insightSource()).isEqualTo(InsightSource.AI);
        assertThat(restored.analysis().insights()).extracting(Insight::title).containsExactly("AI 结论");
        assertThat(restored.sources()).extracting(source -> source.id())
                .containsExactlyElementsOf(before.sources().stream().map(source -> source.id()).toList());
        try (var files = Files.list(sourcePath)) {
            assertThat(files.count()).isEqualTo(sourceCount);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void reanalyzesPersistedSourcesWithNewMappingAndAtomicallyUpdatesManifest() {
        String content = "日期,产品,客户,口径甲,口径乙,资源耗用\n"
                + "2026-06-01,新品A,客户甲,1200,1800,700\n";
        MockMultipartFile file = new MockMultipartFile("files", "双口径.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
        Map<String, String> firstMapping = Map.of("revenue", "口径甲", "cost", "资源耗用");
        Fixture fixture = fixture(new MockMultipartFile[]{file}, firstMapping);
        String oldId = fixture.workspace().current().analysis().id();

        var updated = fixture.workspace().reanalyze(Map.of("revenue", "口径乙", "cost", "资源耗用"));

        assertThat(updated.analysis().id()).isNotEqualTo(oldId);
        assertThat(updated.analysis().summary().revenue()).isEqualByComparingTo("1800.00");
        assertThat(updated.mapping()).containsEntry("revenue", "口径乙");
        assertThat(updated.sources()).singleElement().extracting(source -> source.name()).isEqualTo("双口径.csv");
        assertThat(fixture.analysis().requireSession(oldId).result().id()).isEqualTo(oldId);

        WorkspacePersistenceService restarted = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                new SpreadsheetImportService(1_000_000), new AnalysisService(), new ObjectMapper());
        assertThat(restarted.current().analysis().summary().revenue()).isEqualByComparingTo("1800.00");
    }

    @Test
    void repeatedReanalysisWithAlreadyInstalledMappingReusesCurrentSession() {
        String content = "日期,产品,客户,口径甲,口径乙,资源耗用\n"
                + "2026-06-01,新品A,客户甲,1200,1800,700\n";
        MockMultipartFile file = new MockMultipartFile("files", "双口径.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
        Map<String, String> mapping = Map.of("revenue", "口径甲", "cost", "资源耗用");
        Fixture fixture = fixture(new MockMultipartFile[]{file}, mapping);
        var before = fixture.workspace().current();

        var repeated = fixture.workspace().reanalyze(mapping);

        assertThat(repeated.analysis().id()).isEqualTo(before.analysis().id());
        assertThat(repeated.analysis().summary().revenue()).isEqualByComparingTo("1200.00");
        assertThat(repeated.sources()).extracting(source -> source.id())
                .containsExactlyElementsOf(before.sources().stream().map(source -> source.id()).toList());
    }

    @Test
    void failedReanalysisKeepsPreviouslyUsableSessionAndDurableWorkspace() {
        Fixture fixture = fixture(new MockMultipartFile[]{
                csv("可靠.csv", "2026-01-01,产品A,客户甲,1000,600\n")}, Map.of());
        var before = fixture.workspace().current();
        SpreadsheetImportService failingImporter = new SpreadsheetImportService(1_000_000) {
            @Override
            ImportResult importPersistedFiles(MultipartFile[] files, Map<String, String> mappingOverrides) {
                throw new IllegalStateException("模拟重新读取失败");
            }
        };
        WorkspacePersistenceService failingWorkspace = new WorkspacePersistenceService(
                tempDir.resolve("workspace"), failingImporter, fixture.analysis(), new ObjectMapper());

        assertThatThrownBy(() -> failingWorkspace.reanalyze(Map.of("revenue", "不存在的列")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("原工作区未被覆盖");

        assertThat(fixture.analysis().requireSession(before.analysis().id()).result().id())
                .isEqualTo(before.analysis().id());
        assertThat(failingWorkspace.current().analysis().id()).isEqualTo(before.analysis().id());
        assertThat(failingWorkspace.current().sources()).extracting(source -> source.name())
                .containsExactly("可靠.csv");
    }

    private Fixture fixture(MockMultipartFile[] files, Map<String, String> mapping) {
        AnalysisService analysis = new AnalysisService();
        SpreadsheetImportService importer = new SpreadsheetImportService(1_000_000);
        WorkspacePersistenceService workspace = new WorkspacePersistenceService(tempDir.resolve("workspace"),
                importer, analysis, new ObjectMapper());
        var result = analysis.analyze(importer.importFiles(files, mapping));
        workspace.save(analysis.requireSession(result.id()), files, mapping);
        return new Fixture(analysis, workspace);
    }

    private static MockMultipartFile csv(String name, String rows) {
        String header = "日期,产品,客户,收入,成本\n";
        return new MockMultipartFile("files", name, "text/csv",
                (header + rows).getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile xlsx(String name, String date, String revenue, String cost) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("经营明细");
            Row header = sheet.createRow(0);
            String[] headers = {"日期", "产品", "客户", "收入", "成本"};
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(date);
            row.createCell(1).setCellValue("产品A");
            row.createCell(2).setCellValue("客户甲");
            row.createCell(3).setCellValue(Double.parseDouble(revenue));
            row.createCell(4).setCellValue(Double.parseDouble(cost));
            workbook.write(output);
            return new MockMultipartFile("files", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private record Fixture(AnalysisService analysis, WorkspacePersistenceService workspace) {
    }
}
