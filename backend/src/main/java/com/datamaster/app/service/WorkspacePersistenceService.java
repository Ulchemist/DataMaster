package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.WorkspaceCurrentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** One local, explicitly deletable workspace. Startup never clears this directory. */
@Service
public class WorkspacePersistenceService {
    private static final int LEGACY_MANIFEST_VERSION = 1;
    // v3：字段目录新增“规格型号/送货地址”后，需要让旧清单在下一次重新分析时强制重解析一次源文件，
    // 使新维度落入行数据；重解析完成后清单即以 v3 落盘，超时重试仍按相同口径去重。
    private static final int MANIFEST_VERSION = 3;
    private static final String CURRENT = "current";
    private static final String MANIFEST = "manifest.json";
    private static final String SNAPSHOT = "analysis-session.json.gz";
    private static final Set<String> EXTENSIONS = Set.of("xlsx", "xls", "csv");
    private static final Pattern YEAR_MONTH_NAME = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})\\s*(?:年|[-_./])\\s*(0?[1-9]|1[0-2])\\s*月?");
    private static final Pattern COMPACT_YEAR_MONTH_NAME = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})(0[1-9]|1[0-2])(?!\\d)");

    private final Path root;
    private final SpreadsheetImportService importer;
    private final AnalysisService analysis;
    private final ObjectMapper objectMapper;
    private volatile String cachedAnalysisId;

    @Autowired
    public WorkspacePersistenceService(
            @Value("${datamaster.workspace.path:${user.home}/.datamaster/workspace}") String path,
            SpreadsheetImportService importer, AnalysisService analysis, ObjectMapper objectMapper) {
        this(Path.of(path), importer, analysis, objectMapper);
    }

    WorkspacePersistenceService(Path root, SpreadsheetImportService importer,
                                AnalysisService analysis, ObjectMapper objectMapper) {
        this.root = root.toAbsolutePath().normalize();
        this.importer = importer;
        this.analysis = analysis;
        this.objectMapper = objectMapper;
    }

    public synchronized WorkspaceCurrentResponse save(AnalysisSession session, MultipartFile[] uploads,
                                                       Map<String, String> mapping) {
        if (session == null || session.result() == null) throw new IllegalArgumentException("分析会话不能为空");
        if (uploads == null || uploads.length == 0) throw new IllegalArgumentException("工作区没有可保存的数据源");
        Path staging = createStaging();
        try {
            Path sourcesDirectory = Files.createDirectories(staging.resolve("sources"));
            List<StoredSource> sources = new ArrayList<>();
            for (MultipartFile upload : uploads) {
                if (upload == null || upload.isEmpty()) continue;
                String name = safeDisplayName(upload.getOriginalFilename());
                String extension = extension(name);
                if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("不支持保存的数据源类型：" + name);
                String id = UUID.randomUUID().toString();
                String storedFile = id + "." + extension;
                Path target = sourcesDirectory.resolve(storedFile);
                try (InputStream input = upload.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                sources.add(StoredSource.basic(id, name, storedFile, Files.size(target),
                        safeContentType(upload.getContentType()), sha256(target)));
            }
            if (sources.isEmpty()) throw new IllegalArgumentException("工作区没有可保存的数据源");
            sources = enrichRelationships(sources, session);
            WorkspaceManifest manifest = new WorkspaceManifest(MANIFEST_VERSION, Instant.now(),
                    safeMapping(mapping), List.copyOf(sources));
            writeManifest(staging, manifest);
            writeSnapshot(staging.resolve(SNAPSHOT), session);
            install(staging);
            activateSession(session.result().id());
            return response(session, manifest);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(staging);
            if (ex instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("保存最近工作区失败，原工作区未被覆盖", ex);
        }
    }

    /**
     * Atomically appends a new upload batch to the durable local workspace.  Existing sources are
     * copied unchanged into staging and all sources are recalculated together only after every new
     * file has been validated.  Exact-content duplicates (including duplicates renamed by the
     * user or repeated within the same picker batch) are skipped by SHA-256.
     */
    public synchronized WorkspaceCurrentResponse append(MultipartFile[] uploads,
                                                         Map<String, String> mappingOverrides) {
        importer.validateUploadBatch(uploads);
        recoverInterruptedInstall();
        Path current = currentDirectory();
        boolean hadCurrent = Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS);
        WorkspaceManifest existing = hadCurrent ? readManifest(current)
                : new WorkspaceManifest(MANIFEST_VERSION, Instant.now(), Map.of(), List.of());
        Map<String, String> mergedMapping = mergeMapping(existing.mapping(), mappingOverrides);
        Path staging = createStaging();
        String pendingAnalysisId = null;
        try {
            Path targetSources = Files.createDirectories(staging.resolve("sources"));
            List<StoredSource> sources = new ArrayList<>();
            Set<String> knownHashes = new HashSet<>();
            for (StoredSource source : existing.sources()) {
                Path original = checkedSourcePath(current, source);
                Path target = targetSources.resolve(source.storedFile());
                Files.copy(original, target, StandardCopyOption.REPLACE_EXISTING);
                String hash = hasText(source.sha256()) ? source.sha256() : sha256(original);
                knownHashes.add(hash);
                sources.add(source.withSha256(hash));
            }

            int added = 0;
            for (MultipartFile upload : uploads) {
                if (upload == null || upload.isEmpty()) continue;
                String name = safeDisplayName(upload.getOriginalFilename());
                String extension = extension(name);
                String id = UUID.randomUUID().toString();
                String storedFile = id + "." + extension;
                Path target = targetSources.resolve(storedFile);
                try (InputStream input = upload.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                String hash = sha256(target);
                if (!knownHashes.add(hash)) {
                    Files.deleteIfExists(target);
                    continue;
                }
                sources.add(StoredSource.basic(id, name, storedFile, Files.size(target),
                        safeContentType(upload.getContentType()), hash));
                added++;
            }
            if (added == 0) {
                if (hadCurrent && mergedMapping.equals(existing.mapping())) {
                    deleteQuietly(staging);
                    return current();
                }
                if (!hadCurrent) {
                    deleteQuietly(staging);
                    throw new IllegalArgumentException("选择的文件内容完全重复，没有可追加的数据源");
                }
                // A duplicate upload may accompany a deliberate mapping correction.  Skip the
                // duplicate bytes, but still honor the new confirmed business field mapping.
            }

            AnalysisSession recalculated = analyzeStored(staging, sources, mergedMapping);
            pendingAnalysisId = recalculated.result().id();
            sources = enrichRelationships(sources, recalculated);
            WorkspaceManifest updated = new WorkspaceManifest(MANIFEST_VERSION, Instant.now(),
                    mergedMapping, List.copyOf(sources));
            writeManifest(staging, updated);
            writeSnapshot(staging.resolve(SNAPSHOT), recalculated);
            install(staging);
            activateSession(recalculated.result().id());
            pendingAnalysisId = null;
            return response(recalculated, updated);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(staging);
            analysis.releaseSession(pendingAnalysisId);
            if (ex instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("追加数据源失败，原工作区保持不变", ex);
        }
    }

    public synchronized WorkspaceCurrentResponse current() {
        recoverInterruptedInstall();
        Path current = currentDirectory();
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return WorkspaceCurrentResponse.empty();
        WorkspaceManifest manifest = readManifest(current);
        if (cachedAnalysisId != null) {
            try {
                return response(analysis.requireSession(cachedAnalysisId), manifest);
            } catch (AnalysisService.AnalysisNotFoundException ignored) {
                cachedAnalysisId = null;
            }
        }
        AnalysisSession session;
        AnalysisSession persistedSnapshot = null;
        try {
            session = readSnapshot(current.resolve(SNAPSHOT));
            persistedSnapshot = session;
            validateSnapshot(session, manifest);
            analysis.restoreSession(session);
        } catch (RuntimeException ex) {
            session = reimport(current, manifest);
            session = preserveLegacyInsights(session, persistedSnapshot, manifest);
            // reimport() registers the freshly calculated LOCAL_RULES session.  When legacy AI
            // insights are migrated onto the rebuilt result, replace that cached session as well;
            // otherwise only the first current() response contains the migrated AI result and the
            // next cached read silently falls back to the pre-migration local insights.
            analysis.restoreSession(session);
            writeSnapshotAtomically(current.resolve(SNAPSHOT), session);
        }
        activateSession(session.result().id());
        return response(session, manifest);
    }

    public synchronized WorkspaceCurrentResponse deleteSource(String sourceId) {
        String requested = sourceId == null ? "" : sourceId.strip();
        Path current = currentDirectory();
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("当前没有可删除的数据源");
        }
        WorkspaceManifest manifest = readManifest(current);
        StoredSource removed = manifest.sources().stream().filter(source -> source.id().equals(requested))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到指定的数据源"));
        List<StoredSource> remaining = manifest.sources().stream()
                .filter(source -> !source.id().equals(removed.id())).toList();
        if (remaining.isEmpty()) {
            clear();
            return WorkspaceCurrentResponse.empty();
        }

        AnalysisSession recalculated = analyzeStored(current, remaining, manifest.mapping());
        remaining = enrichRelationships(remaining, recalculated);
        Path staging = createStaging();
        try {
            Path targetSources = Files.createDirectories(staging.resolve("sources"));
            for (StoredSource source : remaining) {
                Files.copy(checkedSourcePath(current, source), targetSources.resolve(source.storedFile()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            WorkspaceManifest updated = new WorkspaceManifest(MANIFEST_VERSION, Instant.now(),
                    manifest.mapping(), List.copyOf(remaining));
            writeManifest(staging, updated);
            writeSnapshot(staging.resolve(SNAPSHOT), recalculated);
            install(staging);
            activateSession(recalculated.result().id());
            return response(recalculated, updated);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(staging);
            throw new IllegalStateException("删除数据源后保存失败，原工作区未被覆盖", ex);
        }
    }

    public synchronized WorkspaceCurrentResponse reanalyze(Map<String, String> mapping) {
        Path current = currentDirectory();
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("当前没有可重新分析的工作区");
        }
        WorkspaceManifest manifest = readManifest(current);
        Map<String, String> updatedMapping = safeMapping(mapping);
        // A timed-out desktop request can be retried while the first server-side recalculation is
        // still completing. Once that first request installs the same mapping, the queued retry
        // must reuse the durable current result instead of parsing every workbook a second time.
        // 旧版本清单（字段目录升级前）即使口径相同也强制重解析一次，让新识别维度落入行数据。
        if (updatedMapping.equals(manifest.mapping()) && manifest.version() == MANIFEST_VERSION) return current();

        Path staging = null;
        String pendingAnalysisId = null;
        try {
            AnalysisSession recalculated = analyzeStored(current, manifest.sources(), updatedMapping);
            pendingAnalysisId = recalculated.result().id();
            List<StoredSource> refreshedSources = enrichRelationships(manifest.sources(), recalculated);
            staging = createStaging();
            Path targetSources = Files.createDirectories(staging.resolve("sources"));
            for (StoredSource source : refreshedSources) {
                Files.copy(checkedSourcePath(current, source), targetSources.resolve(source.storedFile()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            WorkspaceManifest updated = new WorkspaceManifest(MANIFEST_VERSION, Instant.now(),
                    updatedMapping, List.copyOf(refreshedSources));
            writeManifest(staging, updated);
            writeSnapshot(staging.resolve(SNAPSHOT), recalculated);
            install(staging);
            activateSession(recalculated.result().id());
            pendingAnalysisId = null;
            return response(recalculated, updated);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(staging);
            analysis.releaseSession(pendingAnalysisId);
            throw new IllegalStateException("重新分析后保存失败，原工作区未被覆盖", ex);
        }
    }

    /** Persists updated AI insights for the active workspace without touching source files or its manifest. */
    public synchronized void refreshSnapshot(AnalysisSession session) {
        if (session == null || session.result() == null || cachedAnalysisId == null
                || !cachedAnalysisId.equals(session.result().id())) return;
        Path current = currentDirectory();
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return;
        WorkspaceManifest manifest = readManifest(current);
        validateSnapshot(session, manifest);
        writeSnapshotAtomically(current.resolve(SNAPSHOT), session);
    }

    public synchronized void clear() {
        String releasedAnalysisId = cachedAnalysisId;
        cachedAnalysisId = null;
        // Explicit deletion must remove both the durable workspace and the in-memory row session.
        // Otherwise the old analysis remains addressable by its id until the bounded cache evicts it,
        // which is surprising when the user has just deleted the last source or cleared all data.
        analysis.releaseSession(releasedAnalysisId);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            List<Path> targets;
            try (var entries = Files.list(root)) {
                targets = entries.filter(path -> {
                    String name = path.getFileName().toString();
                    return CURRENT.equals(name) || name.startsWith(".backup-")
                            || name.startsWith(".staging-") || name.startsWith(".deleted-");
                }).toList();
            }
            List<Path> trash = new ArrayList<>();
            for (Path target : targets) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                Path deleted = root.resolve(".deleted-" + UUID.randomUUID());
                move(target, deleted);
                trash.add(deleted);
            }
            for (Path deleted : trash) deleteRecursively(deleted);
        } catch (IOException ex) {
            throw new IllegalStateException("清空本地工作区失败", ex);
        }
    }

    private void activateSession(String analysisId) {
        cachedAnalysisId = analysisId;
        // Keep the previous entry as the one bounded fallback session until the client confirms it
        // received the replacement and calls DELETE /api/analysis/{id}. This prevents a timed-out
        // or disconnected renderer from losing its still-visible analysis id mid-recovery.
    }

    private AnalysisSession reimport(Path current, WorkspaceManifest manifest) {
        try {
            return analyzeStored(current, manifest.sources(), manifest.mapping());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("分析快照已损坏，且无法从本地源文件重新计算", ex);
        }
    }

    private AnalysisSession analyzeStored(Path current, List<StoredSource> sources, Map<String, String> mapping) {
        MultipartFile[] files = sources.stream().map(source -> new PathMultipartFile(
                checkedSourcePath(current, source), source.name(), source.contentType())).toArray(MultipartFile[]::new);
        var result = analysis.analyze(importer.importPersistedFiles(files, mapping));
        return analysis.requireSession(result.id());
    }

    private Path checkedSourcePath(Path workspace, StoredSource source) {
        if (source == null || source.storedFile() == null
                || !source.storedFile().matches("[0-9a-fA-F-]+\\.(xlsx|xls|csv)")) {
            throw new IllegalStateException("工作区数据源索引无效");
        }
        Path sourcesDirectory = workspace.resolve("sources").normalize();
        Path path = sourcesDirectory.resolve(source.storedFile()).normalize();
        if (!path.startsWith(sourcesDirectory) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("工作区数据源文件缺失：" + source.name());
        }
        return path;
    }

    private WorkspaceManifest readManifest(Path workspace) {
        try {
            byte[] encoded = Files.readAllBytes(workspace.resolve(MANIFEST));
            WorkspaceManifest manifest;
            try {
                manifest = objectMapper.readValue(encoded, WorkspaceManifest.class);
            } catch (RuntimeException modernFailure) {
                // Jackson 3 treats missing record creator properties more strictly than the
                // version used by the first desktop builds.  Read the deliberately small v1
                // shape explicitly so a pre-upgrade workspace never appears to be lost.
                manifest = readLegacyManifest(encoded, modernFailure);
            }
            if (manifest == null || manifest.version() < LEGACY_MANIFEST_VERSION
                    || manifest.version() > MANIFEST_VERSION || manifest.sources() == null
                    || manifest.sources().isEmpty()) throw new IllegalStateException("工作区清单版本无效");
            manifest.sources().forEach(source -> checkedSourcePath(workspace, source));
            return new WorkspaceManifest(manifest.version(), manifest.savedAt(),
                    safeMapping(manifest.mapping()), manifest.sources().stream()
                    .map(StoredSource::normalized).toList());
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("读取最近工作区清单失败", ex);
        }
    }

    private WorkspaceManifest readLegacyManifest(byte[] encoded, RuntimeException modernFailure) {
        try {
            Object decoded = objectMapper.readValue(encoded, Object.class);
            if (!(decoded instanceof Map<?, ?> rootValue)
                    || !(rootValue.get("version") instanceof Number versionValue)
                    || versionValue.intValue() != LEGACY_MANIFEST_VERSION
                    || !(rootValue.get("sources") instanceof List<?> sourceValues)) {
                throw modernFailure;
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            if (rootValue.get("mapping") instanceof Map<?, ?> rawMapping) {
                rawMapping.forEach((key, value) -> {
                    if (key instanceof String textKey && value instanceof String textValue) {
                        mapping.put(textKey, textValue);
                    }
                });
            }
            List<StoredSource> sources = new ArrayList<>();
            for (Object value : sourceValues) {
                if (!(value instanceof Map<?, ?> source)) throw modernFailure;
                String id = legacyText(source.get("id"));
                String name = legacyText(source.get("name"));
                String storedFile = legacyText(source.get("storedFile"));
                String contentType = legacyText(source.get("contentType"));
                long size = source.get("size") instanceof Number number ? number.longValue() : 0L;
                sources.add(StoredSource.basic(id, name, storedFile, size, contentType, ""));
            }
            Instant savedAt = Instant.EPOCH;
            try {
                String value = legacyText(rootValue.get("savedAt"));
                if (!value.isBlank()) savedAt = Instant.parse(value);
            } catch (RuntimeException ignored) {
                // The timestamp is descriptive only; source identity and files are validated below.
            }
            return new WorkspaceManifest(LEGACY_MANIFEST_VERSION, savedAt,
                    safeMapping(mapping), List.copyOf(sources));
        } catch (RuntimeException legacyFailure) {
            if (legacyFailure != modernFailure) modernFailure.addSuppressed(legacyFailure);
            throw modernFailure;
        }
    }

    private static String legacyText(Object value) {
        return value instanceof String text ? text : "";
    }

    private void writeManifest(Path workspace, WorkspaceManifest manifest) throws IOException {
        try (OutputStream output = Files.newOutputStream(workspace.resolve(MANIFEST))) {
            objectMapper.writeValue(output, manifest);
        }
    }

    private void writeSnapshot(Path target, AnalysisSession session) throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
             GZIPOutputStream gzip = new GZIPOutputStream(file, 64 * 1024)) {
            objectMapper.writeValue(gzip, session);
        }
    }

    private AnalysisSession readSnapshot(Path target) {
        try (InputStream file = Files.newInputStream(target);
             GZIPInputStream gzip = new GZIPInputStream(file, 64 * 1024)) {
            return objectMapper.readValue(gzip, AnalysisSession.class);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("分析快照损坏", ex);
        }
    }

    private void writeSnapshotAtomically(Path target, AnalysisSession session) {
        Path temporary = target.resolveSibling(SNAPSHOT + ".tmp-" + UUID.randomUUID());
        try {
            writeSnapshot(temporary, session);
            move(temporary, target);
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new IllegalStateException("重新计算成功，但更新分析快照失败", ex);
        }
    }

    private static void validateSnapshot(AnalysisSession session, WorkspaceManifest manifest) {
        if (session == null || session.result() == null || session.rows() == null
                || session.result().rowCount() != session.rows().size()
                || session.result().sourceFileCount() != manifest.sources().size()
                || !hasCurrentFinancialSummary(session)) {
            throw new IllegalStateException("分析快照与工作区清单不一致");
        }
    }

    /** FinancialSummary gained explicit comparable-basis totals after the first snapshot format.
     * Jackson reads those missing legacy record properties as null.  A legacy snapshot is still
     * recoverable because the original source files and confirmed mapping are stored alongside it;
     * reject only the derived snapshot so current() deterministically rebuilds the new metrics. */
    private static boolean hasCurrentFinancialSummary(AnalysisSession session) {
        var summary = session.result().summary();
        return summary != null
                && summary.revenue() != null
                && summary.cost() != null
                && summary.grossComparableRevenue() != null
                && summary.grossComparableCost() != null
                && summary.grossProfit() != null
                && summary.expenses() != null
                && summary.operatingComparableRevenue() != null
                && summary.operatingComparableCost() != null
                && summary.operatingComparableExpenses() != null
                && summary.operatingProfit() != null
                && summary.grossMargin() != null
                && summary.operatingMargin() != null
                && !hasLegacyNonPositiveRevenueMargin(session);
    }

    private static AnalysisSession preserveLegacyInsights(AnalysisSession rebuilt, AnalysisSession legacy,
                                                           WorkspaceManifest manifest) {
        if (!isStructurallyMatchingLegacySnapshot(legacy, manifest)
                || legacy.result().insights() == null || legacy.result().insights().isEmpty()) return rebuilt;
        var oldResult = legacy.result();
        var migrated = rebuilt.result().withInsights(oldResult.insights(), oldResult.insightSource(),
                oldResult.insightProviderId(), oldResult.insightModel(), oldResult.insightsGeneratedAt());
        return rebuilt.withResult(migrated);
    }

    /** Only carry user-visible AI results when the snapshot is structurally intact and its sole
     * incompatibility is the comparable-basis fields added to FinancialSummary.  Corrupt or
     * mismatched snapshots are still rebuilt without copying untrusted derived content. */
    private static boolean isStructurallyMatchingLegacySnapshot(AnalysisSession session,
                                                                 WorkspaceManifest manifest) {
        if (session == null || session.result() == null || session.rows() == null
                || session.result().rowCount() != session.rows().size()
                || session.result().sourceFileCount() != manifest.sources().size()) return false;
        var summary = session.result().summary();
        if (summary == null || summary.revenue() == null || summary.cost() == null
                || summary.grossProfit() == null || summary.expenses() == null
                || summary.operatingProfit() == null || summary.grossMargin() == null
                || summary.operatingMargin() == null) return false;
        return summary.grossComparableRevenue() == null || summary.grossComparableCost() == null
                || summary.operatingComparableRevenue() == null || summary.operatingComparableCost() == null
                || summary.operatingComparableExpenses() == null
                || hasLegacyNonPositiveRevenueMargin(session);
    }

    /** Older snapshots divided profit by zero or negative revenue.  The profit amount is still
     * valid, but the resulting positive-looking percentage is not meaningful for returns,
     * discounts or giveaways.  Rebuild those snapshots from the stored source rows so every
     * consumer (UI, PDF, Excel and AI prompts) receives the corrected nullable margin. */
    private static boolean hasLegacyNonPositiveRevenueMargin(AnalysisSession session) {
        var result = session.result();
        boolean breakdown = result.products().stream().anyMatch(value -> invalidMargin(
                        value.revenue(), value.grossMargin(), value.operatingMargin()))
                || result.customers().stream().anyMatch(value -> invalidMargin(
                        value.revenue(), value.grossMargin(), value.operatingMargin()))
                || result.monthly().stream().anyMatch(value -> invalidMargin(
                        value.revenue(), value.grossMargin(), value.operatingMargin()));
        if (breakdown) return true;
        return result.dynamicBreakdowns().stream().flatMap(value -> value.values().stream())
                .anyMatch(value -> invalidMargin(value.revenue(), value.grossMargin(), value.operatingMargin()));
    }

    private static boolean invalidMargin(java.math.BigDecimal revenue, java.math.BigDecimal grossMargin,
                                         java.math.BigDecimal operatingMargin) {
        return revenue != null && revenue.signum() <= 0 && (grossMargin != null || operatingMargin != null);
    }

    private static List<StoredSource> enrichRelationships(List<StoredSource> sources, AnalysisSession session) {
        Map<String, Set<YearMonth>> periodsByName = new HashMap<>();
        session.rows().forEach(row -> {
            if (row.date() == null) return;
            String provenance = row.sourceFile();
            for (StoredSource source : sources) {
                // XLSX provenance includes the selected worksheet ("file.xlsx / sheet").
                // CSV and legacy single-sheet imports normally use the exact file name.
                if (provenance.equals(source.name()) || provenance.startsWith(source.name() + " / ")) {
                    periodsByName.computeIfAbsent(source.name(), ignored -> new LinkedHashSet<>())
                            .add(YearMonth.from(row.date()));
                }
            }
        });
        List<StoredSource> result = new ArrayList<>(sources.size());
        for (StoredSource source : sources) {
            List<YearMonth> rowPeriods = periodsByName.getOrDefault(source.name(), Set.of()).stream()
                    .sorted().toList();
            YearMonth filenamePeriod = filenamePeriod(source.name());
            List<String> periods;
            String basis;
            boolean needsAiReview;
            String note;
            if (!rowPeriods.isEmpty()) {
                periods = rowPeriods.stream().map(YearMonth::toString).toList();
                basis = "DATA_ROWS";
                boolean mismatch = filenamePeriod != null && !rowPeriods.contains(filenamePeriod);
                needsAiReview = mismatch;
                if (mismatch) {
                    note = "文件名月份 " + filenamePeriod + " 与数据行月份 "
                            + String.join("、", periods) + " 不一致，已以数据行为准，建议交由 AI 解释后人工确认";
                } else if (periods.size() == 1) {
                    note = "已按数据行日期识别为 " + periods.get(0) + " 月度批次";
                } else {
                    note = "数据行覆盖 " + periods.get(0) + " 至 " + periods.get(periods.size() - 1)
                            + "，按跨期批次保留";
                }
            } else if (filenamePeriod != null) {
                periods = List.of(filenamePeriod.toString());
                basis = "FILENAME";
                needsAiReview = true;
                note = "数据行无可用日期，暂从文件名推断月份；该推断只用于来源关系，不会静默改写业务数据";
            } else {
                periods = List.of();
                basis = "UNKNOWN";
                needsAiReview = true;
                note = "无法用确定性规则识别月份，可由内置 AI 根据表头与业务语义提出建议后再确认";
            }
            result.add(source.withRelationship(periods, basis, seriesKey(source.name()),
                    needsAiReview, note));
        }
        Map<String, List<Integer>> monthSeriesMembers = new HashMap<>();
        for (int index = 0; index < result.size(); index++) {
            StoredSource source = result.get(index);
            for (String period : source.periods()) {
                monthSeriesMembers.computeIfAbsent(source.seriesKey() + "\u001f" + period,
                        ignored -> new ArrayList<>()).add(index);
            }
        }
        Map<Integer, Set<String>> conflicts = new HashMap<>();
        monthSeriesMembers.forEach((key, indexes) -> {
            long versions = indexes.stream().map(result::get).map(StoredSource::sha256)
                    .filter(WorkspacePersistenceService::hasText).distinct().count();
            if (versions <= 1) return;
            String period = key.substring(key.indexOf('\u001f') + 1);
            indexes.forEach(index -> conflicts.computeIfAbsent(index, ignored -> new LinkedHashSet<>())
                    .add(period));
        });
        conflicts.forEach((index, periods) -> {
            StoredSource source = result.get(index);
            String conflictNote = "同月份 " + String.join("、", periods)
                    + " 存在不同文件版本，可能重复累计；请删除旧版或交由人工/AI 复核";
            result.set(index, source.withRelationship(source.periods(), source.periodBasis(),
                    source.seriesKey(), true, source.relationshipNote() + "；" + conflictNote));
        });
        return List.copyOf(result);
    }

    private static YearMonth filenamePeriod(String filename) {
        if (filename == null) return null;
        Matcher expanded = YEAR_MONTH_NAME.matcher(filename);
        if (expanded.find()) return yearMonth(expanded.group(1), expanded.group(2));
        Matcher compact = COMPACT_YEAR_MONTH_NAME.matcher(filename);
        if (compact.find()) return yearMonth(compact.group(1), compact.group(2));
        return null;
    }

    private static YearMonth yearMonth(String year, String month) {
        try {
            return YearMonth.of(Integer.parseInt(year), Integer.parseInt(month));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String seriesKey(String filename) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = YEAR_MONTH_NAME.matcher(name).replaceAll("");
        name = COMPACT_YEAR_MONTH_NAME.matcher(name).replaceAll("");
        name = name.replaceAll("(?:十[一二]|十|[一二三四五六七八九])月", "")
                .replaceAll("[\\s_\\-.—()（）【】]+", "")
                .toLowerCase(Locale.ROOT);
        return name.isBlank() ? "未命名数据系列" : name;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("计算数据源指纹失败", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", ex);
        }
    }

    private static Map<String, String> mergeMapping(Map<String, String> retained,
                                                     Map<String, String> overrides) {
        Map<String, String> result = new LinkedHashMap<>(safeMapping(retained));
        result.putAll(safeMapping(overrides));
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private WorkspaceCurrentResponse response(AnalysisSession session, WorkspaceManifest manifest) {
        List<WorkspaceCurrentResponse.WorkspaceSource> sources = manifest.sources().stream()
                .map(source -> new WorkspaceCurrentResponse.WorkspaceSource(source.id(), source.name(),
                        source.size(), source.contentType(), source.sha256(), source.periods(),
                        source.periodBasis(), source.seriesKey(), source.needsAiReview(),
                        source.relationshipNote())).toList();
        return new WorkspaceCurrentResponse(session.result(), sources, manifest.mapping());
    }

    private Path createStaging() {
        try {
            Files.createDirectories(root);
            return Files.createTempDirectory(root, ".staging-");
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建工作区临时目录", ex);
        }
    }

    private void install(Path staging) throws IOException {
        Path current = currentDirectory();
        Path backup = root.resolve(".backup-" + UUID.randomUUID());
        boolean hadCurrent = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        if (hadCurrent) move(current, backup);
        try {
            move(staging, current);
        } catch (IOException ex) {
            if (hadCurrent && Files.exists(backup) && !Files.exists(current)) move(backup, current);
            throw ex;
        }
        if (hadCurrent) deleteQuietly(backup);
    }

    private Path currentDirectory() {
        return root.resolve(CURRENT);
    }

    /** Restores the last complete workspace if the process stopped after moving current to a
     * backup but before the replacement directory was installed.  Recovery never removes a valid
     * current workspace and prefers backups over staging directories. */
    private void recoverInterruptedInstall() {
        Path current = currentDirectory();
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> candidates;
        try (var entries = Files.list(root)) {
            candidates = entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().startsWith(".backup-"))
                    .sorted(Comparator.comparingLong(WorkspacePersistenceService::lastModified).reversed())
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("检查工作区恢复点失败", ex);
        }
        RuntimeException invalidBackup = null;
        for (Path candidate : candidates) {
            try {
                readManifest(candidate);
            } catch (RuntimeException ex) {
                invalidBackup = ex;
                continue;
            }
            try {
                move(candidate, current);
                return;
            } catch (IOException ex) {
                throw new IllegalStateException("恢复最近工作区失败，完整备份仍保留在本机", ex);
            }
        }
        if (!candidates.isEmpty()) {
            throw new IllegalStateException("检测到工作区恢复点，但清单或源文件已损坏", invalidBackup);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path value : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(value);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // A stale staging folder is harmless and never becomes the current workspace.
        }
    }

    private static Map<String, String> safeMapping(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        mapping.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                result.put(key.strip(), value.strip());
            }
        });
        return Map.copyOf(result);
    }

    private static String safeDisplayName(String value) {
        if (value == null || value.isBlank()) return "upload.xlsx";
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "_").strip();
        if (name.isBlank()) name = "upload.xlsx";
        return name.length() <= 200 ? name : name.substring(name.length() - 200);
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeContentType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value.strip();
    }

    private record WorkspaceManifest(int version, Instant savedAt, Map<String, String> mapping,
                                     List<StoredSource> sources) {
    }

    private record StoredSource(
            String id,
            String name,
            String storedFile,
            long size,
            String contentType,
            String sha256,
            List<String> periods,
            String periodBasis,
            String seriesKey,
            boolean needsAiReview,
            String relationshipNote
    ) {
        private static StoredSource basic(String id, String name, String storedFile, long size,
                                          String contentType, String sha256) {
            return new StoredSource(id, name, storedFile, size, contentType, sha256,
                    List.of(), "UNKNOWN", WorkspacePersistenceService.seriesKey(name), true,
                    "尚未识别月度关系");
        }

        private StoredSource normalized() {
            List<String> safePeriods = periods == null ? List.of() : periods.stream()
                    .filter(WorkspacePersistenceService::hasText).distinct().sorted().toList();
            return new StoredSource(id, name, storedFile, size, safeContentType(contentType),
                    sha256 == null ? "" : sha256.strip(), safePeriods,
                    hasText(periodBasis) ? periodBasis.strip() : "UNKNOWN",
                    hasText(seriesKey) ? seriesKey.strip() : WorkspacePersistenceService.seriesKey(name),
                    needsAiReview, relationshipNote == null ? "" : relationshipNote.strip());
        }

        private StoredSource withSha256(String value) {
            return new StoredSource(id, name, storedFile, size, contentType, value, periods,
                    periodBasis, seriesKey, needsAiReview, relationshipNote).normalized();
        }

        private StoredSource withRelationship(List<String> valuePeriods, String valueBasis,
                                              String valueSeriesKey, boolean valueNeedsAiReview,
                                              String valueNote) {
            return new StoredSource(id, name, storedFile, size, contentType, sha256, valuePeriods,
                    valueBasis, valueSeriesKey, valueNeedsAiReview, valueNote).normalized();
        }
    }

    private static final class PathMultipartFile implements MultipartFile {
        private final Path path;
        private final String name;
        private final String contentType;

        private PathMultipartFile(Path path, String name, String contentType) {
            this.path = path;
            this.name = name;
            this.contentType = contentType;
        }

        @Override public String getName() { return "files"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return size() == 0; }
        @Override public long getSize() { return size(); }
        private long size() {
            try { return Files.size(path); }
            catch (IOException ex) { throw new IllegalStateException("读取本地数据源大小失败", ex); }
        }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File dest) throws IOException {
            Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
