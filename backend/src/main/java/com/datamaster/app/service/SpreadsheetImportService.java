package com.datamaster.app.service;

import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.AnalysisProfile;
import com.datamaster.app.domain.ColumnProfile;
import com.datamaster.app.domain.FieldMapping;
import com.datamaster.app.domain.MappingIssue;
import com.datamaster.app.domain.QualityReport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.Styles;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SpreadsheetImportService {
    public static final long MAX_UPLOAD_BYTES = 500L * 1024 * 1024;
    public static final long MAX_LEGACY_XLS_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES_PER_IMPORT = 50;
    private static final int DUPLICATE_SCAN_LIMIT = 250_000;
    private static final Pattern SOURCE_YEAR_MONTH = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})\\s*(?:年|[-_./])\\s*(0?[1-9]|1[0-2])\\s*月?");
    private static final Pattern SOURCE_COMPACT_YEAR_MONTH = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})(0[1-9]|1[0-2])(?!\\d)");
    private static final ThreadLocal<DataFormatter> DATA_FORMATTER =
            ThreadLocal.withInitial(SpreadsheetImportService::newDataFormatter);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xlsx", "xls", "csv");

    private final long maxRows;

    private static final Map<Field, Set<String>> ALIASES = Map.ofEntries(
            Map.entry(Field.DATE, aliases("日期", "业务日期", "订单日期", "销售日期", "出库日期", "月份", "年月", "date", "month", "period")),
            Map.entry(Field.PRODUCT, aliases("产品", "产品名称", "产品名", "品名", "商品", "商品名称", "物料名称", "product", "productname", "sku")),
            Map.entry(Field.CUSTOMER, aliases("客户", "客户名称", "客户名", "单位名称", "客户系统", "customer", "customername", "client")),
            Map.entry(Field.CUSTOMER_DETAIL, aliases("送货客户", "终端客户", "门店客户", "收货客户")),
            Map.entry(Field.QUANTITY, aliases("数量", "销量", "销售数量", "出库数量", "件数", "quantity", "qty", "volume")),
            Map.entry(Field.OUTBOUND_QUANTITY, aliases("出库数量", "发货数量", "出货数量", "outboundquantity", "shippedquantity")),
            Map.entry(Field.CONVERTED_QUANTITY, aliases("换算只数", "换算数量", "折算只数", "convertedquantity")),
            Map.entry(Field.REVENUE, aliases("收入", "销售收入", "营业收入", "主营业务收入", "营收", "销售额", "金额", "revenue", "sales", "salesamount", "amount")),
            Map.entry(Field.COST, aliases("成本", "销售成本", "营业成本", "主营业务成本", "cost", "cogs")),
            Map.entry(Field.TRANSFER_COST, aliases("调拨成本", "调拨成本含运费", "调拨成本（含运费）", "transfercost")),
            Map.entry(Field.EXPENSE, aliases("费用", "期间费用", "经营费用", "销售费用", "管理费用", "expense", "expenses", "opex")),
            Map.entry(Field.REGION, aliases("区域", "销售区域", "地区", "大区", "region", "area")),
            Map.entry(Field.DEPARTMENT, aliases("部门", "销售部门", "业务部门", "事业部", "department", "dept")),
            Map.entry(Field.SALES_GROUP, aliases("销售组", "销售团队", "销售小组", "salesgroup", "salesteam")),
            Map.entry(Field.CHANNEL, aliases("渠道", "销售渠道", "渠道分类", "channel")),
            Map.entry(Field.BUSINESS_STATUS, aliases("业务状态", "订单状态", "状态", "businessstatus", "status")),
            Map.entry(Field.PRODUCT_CATEGORY, aliases("产品类别", "产品分类", "商品类别", "品类", "productcategory")),
            Map.entry(Field.PRODUCT_FORM, aliases("产品形态汇报", "产品形态（汇报）", "汇报产品形态", "productform")),
            Map.entry(Field.CATEGORY_L1, aliases("一级分类", "一级品类", "category1")),
            Map.entry(Field.CATEGORY_L2, aliases("二级分类", "二级品类", "category2")),
            Map.entry(Field.CATEGORY_L3, aliases("三级分类", "三级品类", "category3")),
            Map.entry(Field.CATEGORY_L4, aliases("四级分类", "四级品类", "category4")),
            Map.entry(Field.CATEGORY_L5, aliases("五级分类", "五级品类", "category5")),
            Map.entry(Field.CATEGORY_L6, aliases("六级分类", "六级品类", "category6")),
            Map.entry(Field.WAREHOUSE, aliases("仓库", "发货仓", "warehouse")),
            Map.entry(Field.INVENTORY_ORG, aliases("库存组织", "库存机构", "inventoryorganization")),
            Map.entry(Field.GRADE, aliases("等级", "客户等级", "产品等级", "grade")),
            Map.entry(Field.CUSTOMER_ANALYSIS_CATEGORY, aliases("客户分析分类", "客户分类")),
            Map.entry(Field.CUSTOMER_ANALYSIS_LARGE_CATEGORY, aliases("客户分析大类", "客户大类")),
            Map.entry(Field.BUSINESS_ANALYSIS_CATEGORY, aliases("经营分析分类")),
            Map.entry(Field.BUSINESS_ANALYSIS_LARGE_CATEGORY, aliases("经营分析大类")),
            Map.entry(Field.PRODUCT_SPEC, aliases("规格型号", "规格", "型号", "产品规格", "spec")),
            Map.entry(Field.DELIVERY_ADDRESS, aliases("送货地址", "收货地址", "配送地址", "地址", "address"))
    );

    public SpreadsheetImportService(@Value("${datamaster.import.max-rows:1000000}") long maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("datamaster.import.max-rows 必须大于 0");
        this.maxRows = maxRows;
    }

    public ImportResult importFiles(MultipartFile[] files) {
        return importFiles(files, Map.of());
    }

    public ImportResult importFiles(MultipartFile[] files, Map<String, String> mappingOverrides) {
        return importFiles(files, mappingOverrides, true);
    }

    /**
     * Reimports files that have already passed the HTTP upload limit and are stored in the local
     * workspace.  The 500 MiB aggregate limit is deliberately a per-request protection, not a
     * lifetime workspace limit: several independently uploaded monthly workbooks may legitimately
     * exceed it when recalculated together.  Per-file, file-count and row-count guards still apply.
     */
    ImportResult importPersistedFiles(MultipartFile[] files, Map<String, String> mappingOverrides) {
        return importFiles(files, mappingOverrides, false);
    }

    /** Validates one incoming upload batch without parsing it or modifying the workspace. */
    void validateUploadBatch(MultipartFile[] files) {
        validateFiles(files, true);
    }

    private ImportResult importFiles(MultipartFile[] files, Map<String, String> mappingOverrides,
                                     boolean enforceAggregateUploadLimit) {
        validateFiles(files, enforceAggregateUploadLimit);
        List<DataRow> rows = new ArrayList<>();
        QualityCounter quality = new QualityCounter();
        ProfileBuilder profile = new ProfileBuilder();
        Map<String, String> overrides = normalizeOverrides(mappingOverrides);
        int sourceCount = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String filename = safeFilename(file.getOriginalFilename());
            String extension = extension(filename);
            try {
                int before = rows.size();
                if ("csv".equals(extension)) {
                    parseCsv(file, filename, rows, quality, profile, overrides);
                } else if ("xlsx".equals(extension)) {
                    parseXlsx(file, filename, rows, quality, profile, overrides);
                } else {
                    try (InputStream input = new BufferedInputStream(file.getInputStream())) {
                        parseLegacyWorkbook(input, filename, rows, quality, profile, overrides);
                    }
                }
                sourceCount++;
                if (rows.size() == before) {
                    quality.warn(filename + " 未找到可分析的数据行");
                }
            } catch (IOException | RuntimeException ex) {
                if (ex instanceof IllegalArgumentException illegal) {
                    throw illegal;
                }
                throw new IllegalArgumentException("读取文件失败：" + filename + "，" + ex.getMessage(), ex);
            }
        }
        if (sourceCount == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未识别到数据行，请确认首行包含日期、产品、客户、收入、成本等字段");
        }
        AnalysisProfile analysisProfile = profile.build(rows.size(), quality);
        QualityReport report = quality.toReport();
        return new ImportResult(List.copyOf(rows), sourceCount, report, analysisProfile);
    }

    private static void validateFiles(MultipartFile[] files, boolean enforceAggregateUploadLimit) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请选择至少一个 Excel 或 CSV 文件");
        }
        if (files.length > MAX_FILES_PER_IMPORT) {
            throw new IllegalArgumentException("一次最多导入 " + MAX_FILES_PER_IMPORT + " 个文件，请分批处理");
        }
        validateUploadSizes(files, enforceAggregateUploadLimit);
        int usable = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            usable++;
            String filename = safeFilename(file.getOriginalFilename());
            String extension = extension(filename);
            if (!SUPPORTED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("不支持的文件类型：" + filename + "（仅支持 xlsx、xls、csv）");
            }
            if ("xls".equals(extension) && file.getSize() > MAX_LEGACY_XLS_BYTES) {
                throw new IllegalArgumentException(filename + " 是旧版 .xls 且超过 64MB；为避免内存耗尽，"
                        + "请先另存为 .xlsx 或 .csv 后再导入（这两种格式支持最高 500MB）");
            }
        }
        if (usable == 0) throw new IllegalArgumentException("上传文件为空");
    }

    private static Map<String, String> normalizeOverrides(Map<String, String> value) {
        if (value == null || value.isEmpty()) return Map.of();
        Map<String, String> result = new HashMap<>();
        value.forEach((key, selected) -> {
            if (key != null && !key.isBlank() && selected != null && !selected.isBlank()) {
                result.put(normalize(key), selected.strip());
            }
        });
        return Map.copyOf(result);
    }

    private static void validateUploadSizes(MultipartFile[] files, boolean enforceAggregateUploadLimit) {
        long total = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            long size = Math.max(0, file.getSize());
            if (size > MAX_UPLOAD_BYTES) {
                throw new UploadLimitExceededException("文件“" + safeFilename(file.getOriginalFilename())
                        + "”超过 500MB，请拆分后再导入");
            }
            if (enforceAggregateUploadLimit && total > MAX_UPLOAD_BYTES - size) {
                throw new UploadLimitExceededException("一次导入的文件总计超过 500MB，请减少文件或分批导入");
            }
            total += size;
        }
    }

    /**
     * Modern OOXML workbooks are parsed from a disk-backed package with POI's event API.
     * This avoids constructing an XSSFWorkbook object graph for every cell. Styles and the
     * read-only shared-string table are still retained by POI, so the row guard remains
     * necessary even though worksheet XML itself is consumed sequentially.
     */
    private void parseXlsx(MultipartFile file, String filename, List<DataRow> output, QualityCounter quality,
                           ProfileBuilder profile, Map<String, String> overrides)
            throws IOException {
        Path temporaryWorkbook = Files.createTempFile("datamaster-import-", ".xlsx");
        try {
            file.transferTo(temporaryWorkbook);
            try (OPCPackage workbookPackage = OPCPackage.open(temporaryWorkbook.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(workbookPackage, true);
                Styles styles = reader.getStylesTable();
                SharedStrings sharedStrings = reader.getSharedStringsTable();
                int externalLinks = workbookPackage.getPartsByName(
                        Pattern.compile("/xl/externalLinks/externalLink\\d+\\.xml")).size();
                if (externalLinks > 0) {
                    quality.observeExternalWorkbookLinks(externalLinks);
                    quality.warn(filename + " 包含 " + externalLinks
                            + " 个外部工作簿链接；当前只读取文件内缓存值，源文件变化后结果可能已过期");
                }
                List<StreamingSheetCandidate> candidates = discoverXlsxSheets(reader, styles, sharedStrings,
                        filename, overrides);
                if (candidates.isEmpty()) {
                    throw new IllegalArgumentException(filename + " 未识别到包含收入、成本或费用的数据工作表");
                }

                List<StreamingSheetCandidate> nonSummary = candidates.stream()
                        .filter(candidate -> !isSummarySheetName(candidate.sheetName()))
                        .toList();
                List<StreamingSheetCandidate> selected = nonSummary.isEmpty() ? candidates : nonSummary;
                if (selected.size() < candidates.size()) {
                    Set<String> selectedNames = selected.stream().map(StreamingSheetCandidate::sheetName)
                            .collect(Collectors.toSet());
                    String skipped = candidates.stream().filter(candidate -> !selectedNames.contains(candidate.sheetName()))
                            .map(StreamingSheetCandidate::sheetName).collect(Collectors.joining("、"));
                    quality.warn(filename + " 为避免明细与汇总重复计数，已跳过汇总工作表：" + skipped);
                }

                Map<String, StreamingSheetCandidate> selectedByName = selected.stream()
                        .collect(Collectors.toMap(StreamingSheetCandidate::sheetName, candidate -> candidate));
                XSSFReader.SheetIterator iterator = reader.getSheetIterator();
                while (iterator.hasNext()) {
                    try (InputStream sheet = iterator.next()) {
                        StreamingSheetCandidate candidate = selectedByName.get(iterator.getSheetName());
                        if (candidate == null) continue;
                        profile.add(candidate.mapping());
                        validateHeaders(candidate.mapping(), candidate.source(), quality);
                        SheetDataHandler handler = new SheetDataHandler(candidate, output, quality);
                        parseXlsxSheet(sheet, styles, sharedStrings, handler);
                    }
                }
            } catch (OpenXML4JException | SAXException ex) {
                throw new IOException("工作簿格式损坏或无法解析", ex);
            }
        } finally {
            try {
                Files.deleteIfExists(temporaryWorkbook);
            } catch (IOException ignored) {
                // The operating system will eventually clean its temporary directory.
            }
        }
    }

    private static List<StreamingSheetCandidate> discoverXlsxSheets(XSSFReader reader, Styles styles,
                                                                     SharedStrings sharedStrings, String filename,
                                                                     Map<String, String> overrides)
            throws IOException, OpenXML4JException, SAXException {
        List<StreamingSheetCandidate> candidates = new ArrayList<>();
        XSSFReader.SheetIterator iterator = reader.getSheetIterator();
        while (iterator.hasNext()) {
            try (InputStream sheet = iterator.next()) {
                String sheetName = iterator.getSheetName();
                HeaderCaptureHandler handler = new HeaderCaptureHandler();
                try {
                    parseXlsxSheet(sheet, styles, sharedStrings, handler);
                } catch (HeaderCapturedException expected) {
                    // Stopping after the first non-empty row avoids scanning the sheet twice in full.
                }
                StreamingHeader header = handler.header();
                if (header == null) continue;
                String source = filename + " / " + sheetName;
                SemanticMapping mapping = SemanticMapping.create(header.headers(), source, overrides);
                if (header.headers().size() < 2) continue;
                candidates.add(new StreamingSheetCandidate(sheetName, header.rowNumber(), mapping, source));
            }
        }
        return candidates;
    }

    private static void parseXlsxSheet(InputStream sheet, Styles styles, SharedStrings sharedStrings,
                                       XSSFSheetXMLHandler.SheetContentsHandler contentsHandler)
            throws IOException, SAXException {
        XMLReader parser;
        try {
            parser = XMLHelper.newXMLReader();
        } catch (javax.xml.parsers.ParserConfigurationException ex) {
            throw new SAXException("无法初始化安全的工作簿 XML 解析器", ex);
        }
        Set<Integer> precisionColumns = contentsHandler instanceof RawNumericColumnProvider provider
                ? provider.rawNumericColumns() : Set.of();
        RawNumericValueTracker rawNumbers = new RawNumericValueTracker(styles, precisionColumns);
        XSSFSheetXMLHandler.SheetContentsHandler precisionHandler = new PrecisionSheetContentsHandler(
                contentsHandler, rawNumbers);
        XSSFSheetXMLHandler poiHandler = new XSSFSheetXMLHandler(styles, sharedStrings, precisionHandler,
                newDataFormatter(), false);
        RawNumericValueFilter filter = new RawNumericValueFilter(rawNumbers);
        filter.setParent(parser);
        filter.setContentHandler(poiHandler);
        filter.parse(new InputSource(sheet));
    }

    private void parseLegacyWorkbook(InputStream input, String filename, List<DataRow> output,
                                     QualityCounter quality, ProfileBuilder profile,
                                     Map<String, String> overrides)
            throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            List<SheetCandidate> candidates = new ArrayList<>();
            for (Sheet sheet : workbook) {
                Row headerRow = firstNonEmptyRow(sheet);
                if (headerRow == null) continue;
                String source = filename + " / " + sheet.getSheetName();
                SemanticMapping mapping = SemanticMapping.create(excelHeaders(headerRow), source, overrides);
                if (mapping.headers.size() < 2) continue;
                candidates.add(new SheetCandidate(sheet, headerRow, mapping, source));
            }
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(filename + " 未识别到包含收入、成本或费用的数据工作表");
            }

            List<SheetCandidate> nonSummary = candidates.stream()
                    .filter(candidate -> !isSummarySheetName(candidate.sheet().getSheetName()))
                    .toList();
            List<SheetCandidate> selected = nonSummary.isEmpty() ? candidates : nonSummary;
            if (selected.size() < candidates.size()) {
                String skipped = candidates.stream().filter(candidate -> !selected.contains(candidate))
                        .map(candidate -> candidate.sheet().getSheetName()).collect(Collectors.joining("、"));
                quality.warn(filename + " 为避免明细与汇总重复计数，已跳过汇总工作表：" + skipped);
            }

            for (SheetCandidate candidate : selected) {
                Sheet sheet = candidate.sheet();
                Row headerRow = candidate.headerRow();
                SemanticMapping mapping = candidate.mapping();
                String source = candidate.source();
                profile.add(mapping);
                validateHeaders(mapping, source, quality);
                for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || isBlank(row)) continue;
                    Map<Integer, String> rawValues = new LinkedHashMap<>();
                    for (Cell cell : row) rawValues.put(cell.getColumnIndex(), cellText(cell));
                    Map<Field, String> values = new EnumMap<>(Field.class);
                    for (Map.Entry<Field, Integer> entry : mapping.selectedColumns().entrySet()) {
                        Cell cell = row.getCell(entry.getValue(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        values.put(entry.getKey(), cellText(cell));
                    }
                    if (isStructuralNoise(values, mapping)) {
                        quality.recordStructuralNoise();
                        continue;
                    }
                    mapping.recordRow(rawValues);
                    addRow(source, values, rawValues, mapping, output, quality);
                }
            }
        }
    }

    private static boolean isSummarySheetName(String value) {
        String name = normalize(value);
        return name.contains("汇总") || name.contains("总览") || name.contains("统计")
                || name.contains("合计") || name.contains("小计") || name.contains("summary")
                || name.contains("dashboard");
    }

    private void parseCsv(MultipartFile file, String filename, List<DataRow> output, QualityCounter quality,
                          ProfileBuilder profile, Map<String, String> overrides)
            throws IOException {
        Charset charset = detectCsvCharset(file);
        try (InputStream input = new BufferedInputStream(file.getInputStream());
             Reader reader = new InputStreamReader(input, charset);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setIgnoreSurroundingSpaces(true)
                     .get()
                     .parse(reader)) {
            Map<Integer, String> rawHeaders = new LinkedHashMap<>();
            parser.getHeaderMap().forEach((header, index) -> rawHeaders.put(index, header));
            SemanticMapping mapping = SemanticMapping.create(rawHeaders, filename, overrides);
            profile.add(mapping);
            validateHeaders(mapping, filename, quality);
            for (CSVRecord record : parser) {
                Map<Field, String> values = new EnumMap<>(Field.class);
                Map<Integer, String> rawValues = new LinkedHashMap<>();
                rawHeaders.forEach((index, header) -> rawValues.put(index,
                        record.isMapped(header) ? record.get(header) : ""));
                for (Map.Entry<Field, Integer> entry : mapping.selectedColumns().entrySet()) {
                    String header = rawHeaders.get(entry.getValue());
                    values.put(entry.getKey(), header != null && record.isMapped(header) ? record.get(header) : "");
                }
                if (rawValues.values().stream().allMatch(value -> value == null || value.isBlank())) {
                    continue;
                }
                if (isStructuralNoise(values, mapping)) {
                    quality.recordStructuralNoise();
                    continue;
                }
                mapping.recordRow(rawValues);
                addRow(filename, values, rawValues, mapping, output, quality);
            }
        }
    }

    private void addRow(String filename, Map<Field, String> values, Map<Integer, String> rawValues,
                        SemanticMapping mapping, List<DataRow> output, QualityCounter quality) {
        if (output.size() >= maxRows) {
            throw new ImportRowLimitExceededException("导入明细超过安全上限 " + maxRows
                    + " 行。请按月份或业务单元拆分文件；如确需提高，可设置 DATAMASTER_IMPORT_MAX_ROWS，"
                    + "但必须同步增加 Java 堆内存");
        }
        String rawDate = values.get(Field.DATE);
        LocalDate parsedDate = parseDate(rawDate);
        String product = clean(values.get(Field.PRODUCT));
        String customer = clean(values.get(Field.CUSTOMER));
        if (parsedDate == null) {
            if (rawDate == null || rawDate.isBlank()) quality.missingDate++;
            else quality.invalidDateValues++;
            LocalDate inferred = sourcePeriodDate(filename);
            if (inferred != null) {
                parsedDate = inferred;
                quality.sourcePeriodDateFallbacks++;
            }
        }
        if (product == null) quality.missingProduct++;
        if (customer == null) quality.missingCustomer++;

        BigDecimal quantity = parseNumber(values.get(Field.QUANTITY), Field.QUANTITY, mapping, quality);
        BigDecimal outboundQuantity = parseMeasureNumber(values.get(Field.OUTBOUND_QUANTITY));
        BigDecimal convertedQuantity = parseMeasureNumber(values.get(Field.CONVERTED_QUANTITY));
        BigDecimal revenue = parseNumber(values.get(Field.REVENUE), Field.REVENUE, mapping, quality);
        BigDecimal cost = parseNumber(values.get(Field.COST), Field.COST, mapping, quality);
        BigDecimal transferCost = parseMeasureNumber(values.get(Field.TRANSFER_COST));
        BigDecimal expense = parseNumber(values.get(Field.EXPENSE), Field.EXPENSE, mapping, quality);
        Map<String, String> dimensions = new LinkedHashMap<>();
        Set<String> validMetrics = new LinkedHashSet<>();
        Map<String, BigDecimal> measures = new LinkedHashMap<>();
        mapping.selectedColumns().forEach((field, column) -> {
            if (field.dimension() && field != Field.PRODUCT && field != Field.CUSTOMER) {
                String value = clean(rawValues.get(column));
                if (value != null) dimensions.put(field.id(), value);
            }
            if (field.numeric() && isValidNumber(rawValues.get(column))) validMetrics.add(field.id());
        });
        mapping.secondaryDimensionColumns().forEach((fieldId, column) -> {
            String value = clean(rawValues.get(column));
            if (value != null) dimensions.put(fieldId, value);
        });
        measures.put(Field.OUTBOUND_QUANTITY.id(), outboundQuantity);
        measures.put(Field.CONVERTED_QUANTITY.id(), convertedQuantity);
        measures.put(Field.TRANSFER_COST.id(), transferCost);
        DataRow row = new DataRow(filename, parsedDate, product, customer,
                quantity, revenue, cost, expense, dimensions, validMetrics, measures);
        output.add(row);
        quality.record(row, rawFingerprint(filename, rawValues));
    }

    /**
     * Ignores formatting remnants and isolated helper-formula cells outside the business table.
     * A row is noise only when all three business identities and every selected numeric measure
     * are blank.  Values in any mapped quantity, revenue, cost, expense or other selected numeric
     * field keep the row, so legitimate adjustment/return rows are never discarded merely because
     * their dimensions are blank.
     */
    private static boolean isStructuralNoise(Map<Field, String> values, SemanticMapping mapping) {
        boolean hasRelevantMapping = mapping.selectedColumns().keySet().stream().anyMatch(field ->
                field == Field.DATE || field == Field.PRODUCT || field == Field.CUSTOMER || field.numeric());
        // Unknown schemas must remain profileable so AI/manual mapping can identify their fields.
        if (!hasRelevantMapping) return false;
        if (parseDate(values.get(Field.DATE)) != null || hasCellValue(values.get(Field.PRODUCT))
                || hasCellValue(values.get(Field.CUSTOMER))) return false;
        return mapping.selectedColumns().keySet().stream()
                .filter(Field::numeric)
                .noneMatch(field -> isValidNumber(values.get(field)));
    }

    private static boolean hasCellValue(String value) {
        return value != null && !value.isBlank();
    }

    private static LocalDate sourcePeriodDate(String source) {
        if (source == null) return null;
        String filename = source.contains(" / ") ? source.substring(0, source.indexOf(" / ")) : source;
        java.util.regex.Matcher expanded = SOURCE_YEAR_MONTH.matcher(filename);
        if (expanded.find()) return safeYearMonthDate(expanded.group(1), expanded.group(2));
        java.util.regex.Matcher compact = SOURCE_COMPACT_YEAR_MONTH.matcher(filename);
        if (compact.find()) return safeYearMonthDate(compact.group(1), compact.group(2));
        return null;
    }

    private static LocalDate safeYearMonthDate(String year, String month) {
        try {
            return YearMonth.of(Integer.parseInt(year), Integer.parseInt(month)).atDay(1);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Row firstNonEmptyRow(Sheet sheet) {
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && !isBlank(row)) {
                return row;
            }
        }
        return null;
    }

    private static boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK && !DATA_FORMATTER.get().formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, String> excelHeaders(Row row) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            result.put(cell.getColumnIndex(), DATA_FORMATTER.get().formatCellValue(cell));
        }
        return result;
    }

    private static void validateHeaders(SemanticMapping mapping, String filename, QualityCounter quality) {
        Map<Field, Integer> headers = mapping.selectedColumns();
        if (!headers.containsKey(Field.REVENUE) && !headers.containsKey(Field.COST)
                && !headers.containsKey(Field.EXPENSE)) {
            quality.warn(filename + " 尚未确认收入、成本或费用字段；当前仅生成结构画像，"
                    + "可使用 AI 辅助识别或手动映射后重新分析");
        }
        List<String> missing = new ArrayList<>();
        if (!headers.containsKey(Field.DATE)) missing.add("日期");
        if (!headers.containsKey(Field.PRODUCT)) missing.add("产品");
        if (!headers.containsKey(Field.CUSTOMER)) missing.add("客户");
        if (!headers.containsKey(Field.QUANTITY) && !headers.containsKey(Field.OUTBOUND_QUANTITY)
                && !headers.containsKey(Field.CONVERTED_QUANTITY)) missing.add("数量");
        if (!missing.isEmpty()) {
            quality.warn(filename + " 缺少可选字段：" + String.join("、", missing));
        }
        mapping.issues().forEach(issue -> quality.warn(issue.message()));
    }

    private static Set<String> aliases(String... values) {
        return Arrays.stream(values).map(SpreadsheetImportService::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-—()（）\\[\\]【】:/\\\\]+", "")
                .replace("﻿", "");
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        CellType valueType = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (valueType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            } catch (RuntimeException ignored) {
                // DataFormatter below is more tolerant for unusual spreadsheet date formats.
            }
        }
        if (valueType == CellType.NUMERIC) {
            try {
                double numericValue = cell.getNumericCellValue();
                if (Double.isFinite(numericValue)) {
                    return BigDecimal.valueOf(numericValue).stripTrailingZeros().toPlainString();
                }
            } catch (RuntimeException ignored) {
                // Malformed formula caches and unusual cell implementations retain POI's safe fallback.
            }
        }
        return DATA_FORMATTER.get().formatCellValue(cell).strip();
    }

    private static DataFormatter newDataFormatter() {
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        formatter.setUseCachedValuesForFormulaCells(true);
        return formatter;
    }

    private static BigDecimal parseNumber(String value, Field field, SemanticMapping mapping,
                                          QualityCounter quality) {
        if (value == null || value.isBlank() || "-".equals(value.strip())) return BigDecimal.ZERO;
        BigDecimal result = parsedNumber(value);
        if (result != null) return result;
        quality.invalidNumericCells++;
        mapping.recordInvalid(field);
        return BigDecimal.ZERO;
    }

    private static boolean isValidNumber(String value) {
        return parsedNumber(value) != null;
    }

    /** Supplemental selectable measures expose their own validity/coverage in the schema profile.
     * Do not double-count the same bad cell in the legacy selected-core-metric error counter when,
     * for example, one column is mapped as both COST and TRANSFER_COST. */
    private static BigDecimal parseMeasureNumber(String value) {
        BigDecimal parsed = parsedNumber(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    private static String rawFingerprint(String source, Map<Integer, String> rawValues) {
        StringBuilder result = new StringBuilder(source).append('\u001e');
        rawValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result
                .append(entry.getKey()).append('\u001f')
                .append(entry.getValue() == null ? "" : entry.getValue().strip()).append('\u001e'));
        return result.toString();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                .replace('年', '-').replace('月', '-').replace("日", "")
                .replace('/', '-').replace('.', '-');
        List<DateTimeFormatter> dateFormats = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("M-d-yyyy"),
                DateTimeFormatter.ofPattern("M-d-yy"),
                DateTimeFormatter.ofPattern("yyyyMMdd"),
                new DateTimeFormatterBuilder().appendPattern("yyyy-M")
                        .parseDefaulting(ChronoField.DAY_OF_MONTH, 1).toFormatter()
        );
        for (DateTimeFormatter formatter : dateFormats) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return YearMonth.parse(normalized).atDay(1);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Charset detectCsvCharset(MultipartFile file) throws IOException {
        // Validate UTF-8 in a sequential first pass. This costs one extra disk read but
        // avoids retaining a 500 MiB byte array and prevents a late GB18030 character from
        // being misclassified by a small prefix sample.
        try (InputStream input = new BufferedInputStream(file.getInputStream());
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT)
                     .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[16 * 1024];
            while (reader.read(buffer) != -1) {
                // Validation only; the parser reopens the disk-backed multipart stream.
            }
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) return "upload.xlsx";
        String normalized = value.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private enum Field {
        DATE("date", "TIME", "日期"),
        PRODUCT("product", "DIMENSION", "产品"),
        CUSTOMER("customer", "DIMENSION", "客户"),
        CUSTOMER_DETAIL("customerDetail", "DIMENSION", "送货客户"),
        QUANTITY("quantity", "METRIC", "数量"),
        OUTBOUND_QUANTITY("outboundQuantity", "METRIC", "出库数量"),
        CONVERTED_QUANTITY("convertedQuantity", "METRIC", "换算只数"),
        REVENUE("revenue", "METRIC", "收入"),
        COST("cost", "METRIC", "成本"),
        TRANSFER_COST("transferCost", "METRIC", "调拨成本（含运费）"),
        EXPENSE("expense", "METRIC", "费用"),
        REPORTED_PROFIT("reportedProfit", "METRIC_CANDIDATE", "报表利润"),
        REPORTED_MARGIN("reportedMargin", "METRIC_CANDIDATE", "报表利润率"),
        REGION("region", "DIMENSION", "区域"),
        DEPARTMENT("department", "DIMENSION", "部门"),
        SALES_GROUP("salesGroup", "DIMENSION", "销售组"),
        CHANNEL("channel", "DIMENSION", "渠道"),
        BUSINESS_STATUS("businessStatus", "DIMENSION", "业务状态"),
        PRODUCT_CATEGORY("productCategory", "DIMENSION", "产品类别"),
        PRODUCT_FORM("productForm", "DIMENSION", "产品形态"),
        CATEGORY_L1("categoryLevel1", "DIMENSION", "一级分类"),
        CATEGORY_L2("categoryLevel2", "DIMENSION", "二级分类"),
        CATEGORY_L3("categoryLevel3", "DIMENSION", "三级分类"),
        CATEGORY_L4("categoryLevel4", "DIMENSION", "四级分类"),
        CATEGORY_L5("categoryLevel5", "DIMENSION", "五级分类"),
        CATEGORY_L6("categoryLevel6", "DIMENSION", "六级分类"),
        WAREHOUSE("warehouse", "DIMENSION", "仓库"),
        INVENTORY_ORG("inventoryOrganization", "DIMENSION", "库存组织"),
        GRADE("grade", "DIMENSION", "等级"),
        CUSTOMER_ANALYSIS_CATEGORY("customerAnalysisCategory", "DIMENSION", "客户分析分类"),
        CUSTOMER_ANALYSIS_LARGE_CATEGORY("customerAnalysisLargeCategory", "DIMENSION", "客户分析大类"),
        BUSINESS_ANALYSIS_CATEGORY("businessAnalysisCategory", "DIMENSION", "经营分析分类"),
        BUSINESS_ANALYSIS_LARGE_CATEGORY("businessAnalysisLargeCategory", "DIMENSION", "经营分析大类"),
        PRODUCT_SPEC("productSpec", "DIMENSION", "规格型号"),
        DELIVERY_ADDRESS("deliveryAddress", "DIMENSION", "送货地址");

        private final String id;
        private final String role;
        private final String label;

        Field(String id, String role, String label) {
            this.id = id;
            this.role = role;
            this.label = label;
        }

        private String id() { return id; }
        private String role() { return role; }
        private String label() { return label; }
        private boolean dimension() { return "DIMENSION".equals(role); }
        private boolean numeric() { return role.startsWith("METRIC"); }
    }

    private record SheetCandidate(Sheet sheet, Row headerRow, SemanticMapping mapping, String source) {
    }

    private record StreamingHeader(int rowNumber, Map<Integer, String> headers) {
    }

    private record StreamingSheetCandidate(String sheetName, int headerRowNumber,
                                           SemanticMapping mapping, String source) {
    }

    /** Local deterministic semantic mapper.  It proposes candidates; ambiguous financial
     * definitions stay unselected until the user or the AI-assisted mapping flow confirms one. */
    private static final class SemanticMapping {
        private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
        private final String source;
        private final Map<Integer, String> headers;
        private final Map<Field, List<Candidate>> candidates;
        private final Map<Field, Integer> selectedColumns;
        private final List<MappingIssue> issues;
        private final Map<Integer, ColumnStats> columnStats;

        private SemanticMapping(String source, Map<Integer, String> headers,
                                Map<Field, List<Candidate>> candidates,
                                Map<Field, Integer> selectedColumns,
                                List<MappingIssue> issues,
                                Map<Integer, ColumnStats> columnStats) {
            this.source = source;
            this.headers = Map.copyOf(headers);
            this.candidates = candidates;
            this.selectedColumns = Map.copyOf(selectedColumns);
            this.issues = List.copyOf(issues);
            this.columnStats = columnStats;
        }

        private static SemanticMapping create(Map<Integer, String> rawHeaders, String source,
                                              Map<String, String> overrides) {
            Map<Integer, String> headers = new LinkedHashMap<>();
            rawHeaders.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    headers.put(entry.getKey(), entry.getValue().strip());
                }
            });
            Map<Field, List<Candidate>> byField = new EnumMap<>(Field.class);
            Map<Integer, ColumnStats> stats = new LinkedHashMap<>();
            headers.forEach((column, header) -> {
                stats.put(column, new ColumnStats(source, column, header));
                for (Field field : Field.values()) {
                    double score = score(field, header);
                    if (score >= 0.55d) {
                        byField.computeIfAbsent(field, ignored -> new ArrayList<>())
                                .add(new Candidate(source, column, header, field, score));
                    }
                }
            });
            // Some legacy sales exports label this field only as “大类”, but place it directly
            // after “客户分析分类”.  Keep that narrow contextual compatibility without treating
            // every generic “大类” column as a customer classification.
            headers.forEach((column, header) -> {
                String previous = headers.get(column - 1);
                if ("大类".equals(normalize(header)) && previous != null
                        && ALIASES.getOrDefault(Field.CUSTOMER_ANALYSIS_CATEGORY, Set.of())
                        .contains(normalize(previous))) {
                    byField.computeIfAbsent(Field.CUSTOMER_ANALYSIS_LARGE_CATEGORY,
                                    ignored -> new ArrayList<>())
                            .add(new Candidate(source, column, header,
                                    Field.CUSTOMER_ANALYSIS_LARGE_CATEGORY, .94d));
                }
            });
            byField.values().forEach(values -> values.sort(Comparator
                    .comparingDouble(Candidate::score).reversed().thenComparingInt(Candidate::column)));

            Map<Field, Integer> selected = new EnumMap<>(Field.class);
            List<MappingIssue> issues = new ArrayList<>();
            for (Field field : Field.values()) {
                List<Candidate> values = byField.getOrDefault(field, List.of());
                String explicit = overrides.get(normalize(field.id()));
                Candidate choice = null;
                if (explicit != null) {
                    choice = values.stream().filter(value -> matchesOverride(value, explicit)).findFirst().orElse(null);
                    if (choice == null) {
                        choice = headers.entrySet().stream()
                                .map(entry -> new Candidate(source, entry.getKey(), entry.getValue(), field, 1d))
                                // AI may identify a genuinely unfamiliar header in this exact
                                // source.  Do not use a source-qualified column letter from another
                                // file to force an unrelated column here; cross-source fallback is
                                // only allowed among locally recognised candidates above.
                                .filter(value -> matchesExactOverride(value, explicit))
                                .findFirst().orElse(null);
                        if (choice != null) {
                            List<Candidate> expanded = byField.computeIfAbsent(field, ignored -> new ArrayList<>());
                            expanded.add(choice);
                            expanded.sort(Comparator.comparingDouble(Candidate::score).reversed()
                                    .thenComparingInt(Candidate::column));
                            values = expanded;
                        }
                    }
                    if (choice == null) {
                        issues.add(new MappingIssue("BLOCKING", "MAPPING_OVERRIDE_NOT_FOUND_"
                                + field.name(),
                                source + " 中找不到为“" + field.label() + "”指定的列：" + explicit,
                                source, values.stream().map(Candidate::columnId).toList()));
                    }
                } else if (!values.isEmpty()) {
                    choice = defaultChoice(field, values, source, issues);
                }
                if (choice != null) {
                    choice.selected = true;
                    selected.put(field, choice.column());
                }
            }
            return new SemanticMapping(source, headers, byField, selected, issues, stats);
        }

        private static Candidate defaultChoice(Field field, List<Candidate> values, String source,
                                               List<MappingIssue> issues) {
            Candidate top = values.get(0);
            if (field == Field.REPORTED_PROFIT || field == Field.REPORTED_MARGIN) {
                if (values.size() > 1) {
                    issues.add(new MappingIssue("WARNING", "MULTIPLE_PROFIT_CANDIDATES",
                            source + " 检测到多套利润口径，未将其混合或用于推导利润；可由 AI 解释后再确认",
                            source, values.stream().map(Candidate::columnId).toList()));
                }
                return null;
            }
            if (field == Field.COST && values.size() > 1) {
                Candidate second = values.get(1);
                boolean dominantExact = top.score() >= .99d && second.score() < .75d;
                if (!dominantExact) {
                    issues.add(new MappingIssue("BLOCKING", "AMBIGUOUS_COST",
                            source + " 检测到多套成本口径，成本和利润分析已停用；请明确选择一种成本口径，系统不会相加或静默猜测",
                            source, values.stream().map(Candidate::columnId).toList()));
                    return null;
                }
            }
            if (field == Field.REVENUE && values.size() > 1) {
                Candidate second = values.get(1);
                boolean dominantExact = top.score() >= .99d && second.score() < .75d;
                if (!dominantExact) {
                    issues.add(new MappingIssue("BLOCKING", "AMBIGUOUS_REVENUE",
                            source + " 检测到多个收入候选；销售与利润结论已停用，"
                                    + "请明确选择未税、含税或调整后收入口径",
                            source, values.stream().map(Candidate::columnId).toList()));
                    return null;
                }
            }
            if (field.dimension() && values.size() > 1
                    && Math.abs(top.score() - values.get(1).score()) < .001d) {
                issues.add(new MappingIssue("WARNING", "DUPLICATE_DIMENSION_CANDIDATE",
                        source + " 的“" + field.label() + "”存在同分候选，当前选择最左侧列“" + top.header() + "”",
                        source, values.stream().map(Candidate::columnId).toList()));
            }
            return top;
        }

        private static boolean matchesOverride(Candidate candidate, String explicit) {
            String normalized = normalize(explicit);
            if (candidate.columnId().equals(explicit) || normalize(candidate.header()).equals(normalized)) return true;
            String columnName = CellReference.convertNumToColString(candidate.column());
            if (columnName.equalsIgnoreCase(explicit.strip())) return true;
            int qualifier = explicit.lastIndexOf("::");
            return qualifier >= 0 && columnName.equalsIgnoreCase(explicit.substring(qualifier + 2).strip());
        }

        private static boolean matchesExactOverride(Candidate candidate, String explicit) {
            String normalized = normalize(explicit);
            if (candidate.columnId().equals(explicit) || normalize(candidate.header()).equals(normalized)) return true;
            return !explicit.contains("::")
                    && CellReference.convertNumToColString(candidate.column()).equalsIgnoreCase(explicit.strip());
        }

        private static double score(Field field, String header) {
            String value = normalize(header);
            if (value.isEmpty()) return 0;
            Set<String> exact = ALIASES.getOrDefault(field, Set.of());
            if (exact.contains(value)) {
                if (field == Field.REVENUE && "金额".equals(header.strip())) return .96d;
                return 1d;
            }
            if (field == Field.REVENUE) {
                if (value.contains("价税合计") && value.contains("调后")) return .90d;
                if (value.contains("价税合计")) return .86d;
                if (value.endsWith("金额") || value.contains("销售额") || value.contains("营业收入")) return .82d;
            }
            if (field == Field.PRODUCT_FORM && value.equals("产品形态")) return .82d;
            if (field == Field.COST && value.contains("成本")) {
                if (value.contains("单只") || value.contains("单位") || value.contains("单价")) return .68d;
                return .84d;
            }
            if (field == Field.EXPENSE && (value.endsWith("费用") || value.endsWith("费"))
                    && !containsAny(value, "归集", "运费", "成本", "单价", "费率", "税费")) return .76d;
            if (field == Field.REPORTED_MARGIN
                    && (value.contains("毛利率") || value.contains("利润率"))) return .92d;
            if (field == Field.REPORTED_PROFIT && !value.contains("毛利率") && !value.contains("利润率")
                    && (value.contains("毛利") || value.endsWith("单利") || value.endsWith("利润"))) return .88d;
            return 0;
        }

        private static boolean containsAny(String value, String... fragments) {
            for (String fragment : fragments) {
                if (value.contains(fragment)) return true;
            }
            return false;
        }

        private boolean hasFinancialCandidate() {
            return candidates.containsKey(Field.REVENUE) || candidates.containsKey(Field.COST)
                    || candidates.containsKey(Field.EXPENSE) || candidates.containsKey(Field.REPORTED_PROFIT);
        }

        private Map<Field, Integer> selectedColumns() { return selectedColumns; }
        private List<MappingIssue> issues() { return issues; }

        private Set<Integer> numericColumns() {
            return candidates.entrySet().stream()
                    .filter(entry -> entry.getKey().numeric())
                    .flatMap(entry -> entry.getValue().stream())
                    .map(Candidate::column)
                    .collect(Collectors.toUnmodifiableSet());
        }

        private Map<String, Integer> secondaryDimensionColumns() {
            Map<String, Integer> result = new LinkedHashMap<>();
            candidates.forEach((field, group) -> {
                if (!field.dimension()) return;
                group.stream().filter(value -> !value.selected).forEach(value ->
                        result.put(secondaryDimensionId(field, value.column()), value.column()));
            });
            return Map.copyOf(result);
        }

        private void recordRow(Map<Integer, String> values) {
            columnStats.forEach((column, stats) -> stats.record(values.get(column)));
            candidates.values().forEach(group -> group.forEach(candidate -> candidate.record(values.get(candidate.column()))));
        }

        private void recordInvalid(Field field) {
            // Candidate validity is calculated from the raw source value in recordRow().
        }

        private List<FieldMapping> mappings() {
            List<FieldMapping> result = new ArrayList<>();
            candidates.forEach((field, group) -> {
                List<String> all = group.stream().map(Candidate::columnId).toList();
                group.forEach(value -> {
                    List<String> alternatives = all.stream()
                            .filter(id -> !id.equals(value.columnId())).toList();
                    if (field.dimension() && !value.selected) {
                        result.add(value.toSecondaryDimensionDto(
                                secondaryDimensionId(field, value.column()), alternatives));
                    } else {
                        result.add(value.toDto(alternatives));
                    }
                });
            });
            return result;
        }

        private static String secondaryDimensionId(Field field, int column) {
            return field.id() + "__" + CellReference.convertNumToColString(column).toLowerCase(Locale.ROOT);
        }

        private List<ColumnProfile> columns() {
            return columnStats.values().stream().map(ColumnStats::toDto).toList();
        }
    }

    private static final class Candidate {
        private final String source;
        private final int column;
        private final String header;
        private final Field field;
        private final double score;
        private boolean selected;
        private long rowCount;
        private long nonBlankCount;
        private long validCount;

        private Candidate(String source, int column, String header, Field field, double score) {
            this.source = source;
            this.column = column;
            this.header = header;
            this.field = field;
            this.score = score;
        }

        private int column() { return column; }
        private String header() { return header; }
        private double score() { return score; }
        private String columnId() { return source + "::" + CellReference.convertNumToColString(column); }

        private void record(String raw) {
            rowCount++;
            if (raw == null || raw.isBlank() || "-".equals(raw.strip())) return;
            nonBlankCount++;
            if (!field.numeric() || isValidNumber(raw)) validCount++;
        }

        private FieldMapping toDto(List<String> alternatives) {
            return new FieldMapping(columnId(), field.id(), field.role(), header, source, column,
                    decimal(score), selected, nonBlankCount, validCount, rowCount,
                    ratio(nonBlankCount, rowCount), ratio(validCount, rowCount), alternatives);
        }

        private FieldMapping toSecondaryDimensionDto(String fieldId, List<String> alternatives) {
            String displayHeader = header + "（" + CellReference.convertNumToColString(column) + " 列）";
            return new FieldMapping(columnId(), fieldId, "DYNAMIC_DIMENSION", displayHeader, source, column,
                    decimal(score), true, nonBlankCount, validCount, rowCount,
                    ratio(nonBlankCount, rowCount), ratio(validCount, rowCount), alternatives);
        }
    }

    private static final class ColumnStats {
        private static final int DISTINCT_SAMPLE_LIMIT = 1_000;
        private final String source;
        private final int column;
        private final String header;
        private long rows;
        private long nonBlank;
        private long numeric;
        private long negative;
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;
        private final Set<String> distinct = new HashSet<>();
        private boolean distinctTruncated;

        private ColumnStats(String source, int column, String header) {
            this.source = source;
            this.column = column;
            this.header = header;
        }

        private void record(String raw) {
            rows++;
            if (raw == null || raw.isBlank()) return;
            String value = raw.strip();
            nonBlank++;
            if (!distinctTruncated) {
                distinct.add(value);
                if (distinct.size() > DISTINCT_SAMPLE_LIMIT) {
                    distinctTruncated = true;
                    distinct.clear();
                }
            }
            BigDecimal number = parsedNumber(value);
            if (number == null) return;
            numeric++;
            if (number.signum() < 0) negative++;
            sum = sum.add(number);
            min = min == null || number.compareTo(min) < 0 ? number : min;
            max = max == null || number.compareTo(max) > 0 ? number : max;
        }

        private ColumnProfile toDto() {
            String type;
            if (nonBlank == 0) type = "EMPTY";
            else if ((double) numeric / nonBlank >= .90d) type = "NUMBER";
            else type = "TEXT";
            BigDecimal mean = numeric == 0 ? null : sum.divide(BigDecimal.valueOf(numeric), 4, RoundingMode.HALF_UP);
            return new ColumnProfile(source + "::" + CellReference.convertNumToColString(column), header,
                    source, column, type, nonBlank, numeric, rows, ratio(nonBlank, rows), min, max, mean,
                    ratio(negative, numeric), distinctTruncated ? DISTINCT_SAMPLE_LIMIT : distinct.size(),
                    distinctTruncated);
        }
    }

    private static final class ProfileBuilder {
        private final List<SemanticMapping> mappings = new ArrayList<>();

        private void add(SemanticMapping value) {
            if (!mappings.contains(value)) mappings.add(value);
        }

        private AnalysisProfile build(long totalRows, QualityCounter quality) {
            List<FieldMapping> fields = mappings.stream().flatMap(value -> value.mappings().stream()).toList();
            Map<String, Long> invalidByColumn = new LinkedHashMap<>();
            fields.stream().filter(value -> value.role().startsWith("METRIC"))
                    .forEach(value -> invalidByColumn.merge(value.columnId(),
                            Math.max(0L, value.nonBlankCount() - value.validCount()), Math::max));
            long profiledInvalidNumericCells = invalidByColumn.values().stream()
                    .mapToLong(Long::longValue).sum();
            quality.observeProfiledNumericErrors(profiledInvalidNumericCells);
            List<MappingIssue> issues = new ArrayList<>(mappings.stream()
                    .flatMap(value -> value.issues().stream()).toList());
            fields.stream().filter(FieldMapping::selected).filter(value -> value.role().startsWith("METRIC"))
                    .filter(value -> value.validCoverage().compareTo(new BigDecimal("0.95")) < 0)
                    .forEach(value -> {
                        String message = value.source() + " 的“" + value.header() + "”有效覆盖率仅 "
                                + value.validCoverage().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                                + "%，相关利润能力已降级";
                        issues.add(new MappingIssue("WARNING", "LOW_FIELD_COVERAGE", message,
                                value.source(), List.of(value.columnId())));
                        quality.warn(message);
                    });
            List<String> dimensions = fields.stream().filter(FieldMapping::selected)
                    .filter(value -> value.role().endsWith("DIMENSION")).map(FieldMapping::fieldId)
                    .distinct().toList();
            List<String> metrics = fields.stream().filter(FieldMapping::selected)
                    .filter(value -> value.role().startsWith("METRIC")).map(FieldMapping::fieldId)
                    .distinct().toList();
            List<ColumnProfile> columns = mappings.stream().flatMap(value -> value.columns().stream()).toList();
            return new AnalysisProfile(fields, issues, dimensions, metrics, totalRows, columns);
        }
    }

    private static BigDecimal parsedNumber(String value) {
        if (value == null || value.isBlank() || "-".equals(value.strip())) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        boolean negativeByParentheses = normalized.startsWith("(") && normalized.endsWith(")");
        boolean percent = normalized.endsWith("%") || normalized.endsWith("％");
        normalized = normalized.replaceAll("[￥¥$,，元\\s]", "")
                .replace("(", "").replace(")", "")
                .replace("%", "").replace("％", "");
        if (normalized.startsWith("#")) return null;
        try {
            BigDecimal result = new BigDecimal(normalized);
            if (percent) {
                result = result.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
            }
            return negativeByParentheses ? result.negate() : result;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(4);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /** Marker used by the SAX bridge to preserve source precision only for metric columns.
     * Dimension and identifier columns continue to use Excel's formatted display text. */
    private interface RawNumericColumnProvider {
        Set<Integer> rawNumericColumns();
    }

    private static final class PrecisionSheetContentsHandler
            implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final XSSFSheetXMLHandler.SheetContentsHandler delegate;
        private final RawNumericValueTracker rawNumbers;

        private PrecisionSheetContentsHandler(XSSFSheetXMLHandler.SheetContentsHandler delegate,
                                              RawNumericValueTracker rawNumbers) {
            this.delegate = delegate;
            this.rawNumbers = rawNumbers;
        }

        @Override
        public void startRow(int rowNum) {
            delegate.startRow(rowNum);
        }

        @Override
        public void endRow(int rowNum) {
            delegate.endRow(rowNum);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            String rawValue = rawNumbers.rawValue(cellReference);
            delegate.cell(cellReference, rawValue == null ? formattedValue : rawValue, comment);
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            delegate.headerFooter(text, isHeader, tagName);
        }
    }

    /**
     * POI's event reader normally exposes only DataFormatter output to SheetContentsHandler.
     * That is correct for labels and dates, but a metric formatted as "0.00" would permanently
     * lose source decimals before it reaches BigDecimal. This tracker captures the worksheet XML
     * value for recognised numeric metric columns and makes it available to the callback without
     * materialising the sheet or changing text/date rendering.
     */
    private static final class RawNumericValueTracker {
        private final Styles styles;
        private final Set<Integer> precisionColumns;
        private String cellReference;
        private boolean preserveCurrentCell;
        private boolean insideValue;
        private StringBuilder value;
        private String capturedCellReference;
        private String capturedValue;

        private RawNumericValueTracker(Styles styles, Set<Integer> precisionColumns) {
            this.styles = styles;
            this.precisionColumns = precisionColumns == null ? Set.of() : Set.copyOf(precisionColumns);
        }

        private void startCell(String reference, String type, String styleIndex) {
            cellReference = reference;
            capturedCellReference = null;
            capturedValue = null;
            int column = reference == null ? -1 : new CellReference(reference).getCol();
            boolean numericType = type == null || type.isBlank() || "n".equals(type);
            preserveCurrentCell = numericType && precisionColumns.contains(column)
                    && !isDateStyle(styleIndex);
        }

        private boolean isDateStyle(String styleIndex) {
            if (styleIndex == null || styleIndex.isBlank()) return false;
            try {
                XSSFCellStyle style = styles.getStyleAt(Integer.parseInt(styleIndex));
                return style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        private void startValue() {
            insideValue = true;
            value = preserveCurrentCell ? new StringBuilder() : null;
        }

        private void characters(char[] characters, int start, int length) {
            if (insideValue && value != null) value.append(characters, start, length);
        }

        private void endValue() {
            if (value != null) {
                String raw = value.toString().strip();
                if (!raw.isEmpty() && parsedNumber(raw) != null) {
                    capturedCellReference = cellReference;
                    capturedValue = raw;
                }
            }
            insideValue = false;
            value = null;
        }

        private String rawValue(String reference) {
            if (capturedValue == null || reference == null || !reference.equals(capturedCellReference)) return null;
            return capturedValue;
        }

        private void endCell() {
            cellReference = null;
            preserveCurrentCell = false;
            insideValue = false;
            value = null;
            capturedCellReference = null;
            capturedValue = null;
        }
    }

    /** SAX filter that observes raw worksheet values while POI retains responsibility for
     * shared strings, formulas, styles, errors and comments. */
    private static final class RawNumericValueFilter extends XMLFilterImpl {
        private final RawNumericValueTracker tracker;

        private RawNumericValueFilter(RawNumericValueTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes)
                throws SAXException {
            String name = xmlElementName(localName, qName);
            if ("c".equals(name)) {
                tracker.startCell(attributes.getValue("r"), attributes.getValue("t"), attributes.getValue("s"));
            } else if ("v".equals(name)) {
                tracker.startValue();
            }
            super.startElement(uri, localName, qName, attributes);
        }

        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            tracker.characters(characters, start, length);
            super.characters(characters, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = xmlElementName(localName, qName);
            if ("v".equals(name)) tracker.endValue();
            super.endElement(uri, localName, qName);
            if ("c".equals(name)) tracker.endCell();
        }

        private static String xmlElementName(String localName, String qName) {
            if (localName != null && !localName.isBlank()) return localName;
            if (qName == null) return "";
            int colon = qName.indexOf(':');
            return colon < 0 ? qName : qName.substring(colon + 1);
        }
    }

    private static final class HeaderCaptureHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private Map<Integer, String> cells = Map.of();
        private int nextColumn;
        private StreamingHeader header;

        @Override
        public void startRow(int rowNum) {
            cells = new HashMap<>();
            nextColumn = 0;
        }

        @Override
        public void endRow(int rowNum) {
            if (cells.values().stream().noneMatch(value -> value != null && !value.isBlank())) return;
            header = new StreamingHeader(rowNum, Map.copyOf(cells));
            throw new HeaderCapturedException();
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference == null ? nextColumn : new CellReference(cellReference).getCol();
            nextColumn = column + 1;
            cells.put(column, formattedValue == null ? "" : formattedValue);
        }

        private StreamingHeader header() {
            return header;
        }
    }

    private final class SheetDataHandler
            implements XSSFSheetXMLHandler.SheetContentsHandler, RawNumericColumnProvider {
        private final StreamingSheetCandidate candidate;
        private final List<DataRow> output;
        private final QualityCounter quality;
        private Map<Field, String> values = Map.of();
        private Map<Integer, String> rawValues = Map.of();
        private int currentRow;
        private int nextColumn;

        private SheetDataHandler(StreamingSheetCandidate candidate, List<DataRow> output, QualityCounter quality) {
            this.candidate = candidate;
            this.output = output;
            this.quality = quality;
        }

        @Override
        public Set<Integer> rawNumericColumns() {
            return candidate.mapping().numericColumns();
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = rowNum;
            nextColumn = 0;
            values = new EnumMap<>(Field.class);
            rawValues = new LinkedHashMap<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow <= candidate.headerRowNumber()
                    || rawValues.values().stream().allMatch(value -> value == null || value.isBlank())) return;
            candidate.mapping().selectedColumns().forEach((field, column) ->
                    values.put(field, rawValues.getOrDefault(column, "")));
            if (isStructuralNoise(values, candidate.mapping())) {
                quality.recordStructuralNoise();
                return;
            }
            candidate.mapping().recordRow(rawValues);
            addRow(candidate.source(), values, rawValues, candidate.mapping(), output, quality);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference == null ? nextColumn : new CellReference(cellReference).getCol();
            nextColumn = column + 1;
            if (currentRow <= candidate.headerRowNumber()) return;
            rawValues.put(column, formattedValue == null ? "" : formattedValue);
        }
    }

    private static final class HeaderCapturedException extends RuntimeException {
        private HeaderCapturedException() {
            super(null, null, false, false);
        }
    }

    public record ImportResult(List<DataRow> rows, int sourceFileCount, QualityReport quality,
                               AnalysisProfile profile) {
        public ImportResult(List<DataRow> rows, int sourceFileCount, QualityReport quality) {
            this(rows, sourceFileCount, quality, AnalysisProfile.empty());
        }

        public ImportResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
            profile = profile == null ? AnalysisProfile.empty() : profile;
        }
    }

    public static final class UploadLimitExceededException extends IllegalArgumentException {
        public UploadLimitExceededException(String message) {
            super(message);
        }
    }

    static final class ImportRowLimitExceededException extends IllegalArgumentException {
        private ImportRowLimitExceededException(String message) {
            super(message);
        }
    }

    private static final class QualityCounter {
        private int missingDate;
        private int invalidDateValues;
        private int sourcePeriodDateFallbacks;
        private int missingProduct;
        private int missingCustomer;
        private int invalidNumericCells;
        private int profiledNumericErrors;
        private int externalWorkbookLinks;
        private int recordCount;
        private long structuralNoiseRows;
        private int negativeRows;
        private long duplicateRows;
        private int duplicateRowsScanned;
        private Set<String> rowsForDuplicateScan = new HashSet<>();
        private boolean duplicateScanTruncated;
        private final List<String> warnings = new ArrayList<>();

        private void warn(String warning) {
            if (!warnings.contains(warning)) warnings.add(warning);
        }

        private void observeProfiledNumericErrors(long count) {
            profiledNumericErrors = Math.max(profiledNumericErrors,
                    count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
        }

        private void observeExternalWorkbookLinks(int count) {
            long total = (long) externalWorkbookLinks + Math.max(0, count);
            externalWorkbookLinks = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }

        private void recordStructuralNoise() {
            structuralNoiseRows++;
        }

        private void record(DataRow row, String rawFingerprint) {
            recordCount++;
            if (row.quantity().signum() < 0 || row.revenue().signum() < 0
                    || row.cost().signum() < 0 || row.expense().signum() < 0) {
                negativeRows++;
            }
            if (rowsForDuplicateScan == null) return;
            if (duplicateRowsScanned >= DUPLICATE_SCAN_LIMIT) {
                rowsForDuplicateScan.clear();
                rowsForDuplicateScan = null;
                duplicateScanTruncated = true;
                return;
            }
            duplicateRowsScanned++;
            if (!rowsForDuplicateScan.add(rawFingerprint)) duplicateRows++;
        }

        private QualityReport toReport() {
            int totalRows = recordCount;
            if (invalidNumericCells > 0) {
                warn("有 " + invalidNumericCells + " 个数值或公式结果无法解析；相关字段能力已降级，请修复后重算");
            }
            if (profiledNumericErrors > invalidNumericCells) {
                warn("全部候选指标列共发现 " + profiledNumericErrors
                        + " 个不可解析数值或公式错误；其中未选口径不会进入当前计算");
            }
            if (missingDate > 0) warn("有 " + missingDate + " 行缺少日期");
            if (invalidDateValues > 0) {
                warn("有 " + invalidDateValues + " 行日期无法直接解析；已保留带业务数据的明细");
            }
            if (sourcePeriodDateFallbacks > 0) {
                warn("其中 " + sourcePeriodDateFallbacks
                        + " 行已按文件名中的明确年月归入月度，原始日期问题仍保留在质量提示中");
            }
            if (missingProduct > 0) warn("有 " + missingProduct + " 行缺少产品");
            if (missingCustomer > 0) warn("有 " + missingCustomer + " 行缺少客户");
            if (structuralNoiseRows > 0) {
                warn("已跳过 " + structuralNoiseRows
                        + " 行结构噪声：日期、产品、客户与已选核心指标均为空");
            }
            if (duplicateRows > 0) warn("发现 " + duplicateRows + " 行完全重复明细，请确认是否需要保留");
            if (duplicateScanTruncated) {
                warn("为控制大文件内存占用，完全重复检查仅覆盖前 " + DUPLICATE_SCAN_LIMIT + " 行");
            }
            if (negativeRows > 0) warn("发现 " + negativeRows + " 行负数，可能是退货、冲销或录入异常");
            return new QualityReport(totalRows, totalRows, missingDate, missingProduct,
                    missingCustomer, invalidNumericCells, warnings,
                    Math.max(profiledNumericErrors, invalidNumericCells), externalWorkbookLinks);
        }
    }
}
