package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisChatRequest;
import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.ProviderUpdate;
import com.datamaster.app.domain.QualityReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisChatServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void sendsOnlyBoundedAggregateContextAndReturnsEvidenceBoundWhitelistedAdjustments() {
        AnalysisSession session = session();
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return """
                            ```json
                            {"answer":"当前产品组合的收入贡献存在集中，建议按销售组继续核验。",
                             "evidenceIds":["totals.revenue","group_1.profit","made.up"],
                             "suggestions":[
                               {"type":"setFilter","label":"只看 138 分类","dimension":"businessAnalysisCategory","values":["138"],"value":null},
                               {"type":"setMetric","label":"切换销售额指标","dimension":null,"values":[],"value":"revenue"},
                               {"type":"openDrilldown","label":"按销售组下钻","dimension":"salesGroup","values":[],"value":null},
                               {"type":"setFilter","label":"不存在的筛选","dimension":"channel","values":["虚构渠道"],"value":null}
                             ]}
                            ```
                            """;
                });
        AnalysisChatRequest request = new AnalysisChatRequest("deepseek", "product", "哪些产品值得继续下钻？",
                Map.of(), List.of(new AnalysisChatRequest.ChatMessage("user", "继续看产品组合")),
                new AnalysisChatRequest.ChatContext(Map.of("region", List.of("华东")), "profit",
                        "outboundQuantity", "transferCost"));

        var response = service.chat("analysis-id", session, request);

        assertThat(prompt.get()).contains("\"aggregateOnly\":true", "\"rawRowsIncluded\":false",
                        "\"aggregateContext\"", "\"actionCatalog\"", "totals.revenue",
                        "\"grossComparableRevenue\"", "\"grossComparableCost\"")
                .doesNotContain("source.xlsx", "原始备注", "api-key", "水饺", "汤圆", "客户甲", "客户乙");
        assertThat(response.answer()).contains("当前产品组合", "当前筛选后的本地聚合结果");
        assertThat(response.message()).isEqualTo(response.answer());
        assertThat(response.reply()).isEqualTo(response.answer());
        assertThat(response.suggestions()).hasSize(3);
        assertThat(response.suggestions()).extracting(item -> item.type())
                .containsExactly("setFilter", "setMetric", "openDrilldown");
        assertThat(response.evidence()).extracting(item -> item.id())
                .containsExactly("totals.revenue", "group_1.profit");
        assertThat(response.evidence().get(0).display()).contains("销售额", "元");
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void fallsBackToLocalEvidenceAfterTwoNumericClaimResponsesWithoutEchoingInventedAmounts() {
        AtomicInteger attempts = new AtomicInteger();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    attempts.incrementAndGet();
                    return "{\"answer\":\"销售额预计增长20%\",\"evidenceIds\":[],\"suggestions\":[]}";
                });
        AnalysisChatRequest request = new AnalysisChatRequest("deepseek", "product", "预测一下",
                Map.of(), List.of(), null);

        var response = service.chat("analysis-id", session(), request);

        assertThat(attempts).hasValue(2);
        assertThat(response.answer())
                .contains("模型回答未通过本地证据校验", "当前筛选后的本地聚合结果")
                .doesNotContain("20", "预计增长");
        assertThat(response.evidence()).extracting(item -> item.id())
                .containsExactly("scope.period", "totals.revenue", "totals.profit",
                        "totals.profitCoverage", "totals.quantity");
        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void acceptsNumericClassificationLabelsFromTheAggregateEvenBeyondTheActionCatalogLimit() {
        List<DataRow> rows = new java.util.ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            rows.add(classificationRow(String.format("%03d", index), String.valueOf(100 + index)));
        }
        rows.add(classificationRow("999", "10000"));
        AnalysisSession session = session(rows);
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return "{\"answer\":\"经营分析分类 999 的盈利质量需要优先复核。\","
                            + "\"evidenceIds\":[\"group_1.margin\"],\"suggestions\":[]}";
                });
        AnalysisChatRequest request = new AnalysisChatRequest("deepseek", "product",
                "哪些经营分析分类销售额高但利润率低？", Map.of(), List.of(),
                new AnalysisChatRequest.ChatContext(Map.of(), "revenue", "outboundQuantity", "cost"));

        var response = service.chat("analysis-id", session, request);

        assertThat(prompt.get()).contains("\"groupLabel\":\"999\"");
        assertThat(response.answer()).contains("经营分析分类 999");
    }

    @Test
    void currentCustomerChannelAndSalesGroupSelectionsBecomeLocalFiltersButRemainAnonymous() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return "{\"answer\":\"当前选择范围已按页面焦点重新聚合。\","
                            + "\"evidenceIds\":[\"totals.revenue\"],\"suggestions\":[]}";
                });

        AnalysisChatRequest customerRequest = new AnalysisChatRequest("deepseek", "customer", "看产品组合",
                Map.of(), List.of(), new AnalysisChatRequest.ChatContext(Map.of(), "profit",
                "outboundQuantity", "transferCost", null, "客户甲", null, null, null));
        service.chat("analysis-id", session(), customerRequest);
        assertThat(prompt.get()).contains("\"groupBy\":\"product\"", "\"customer\":1")
                .doesNotContain("客户甲", "水饺", "汤圆");

        AnalysisChatRequest channelRequest = new AnalysisChatRequest("deepseek", "customer", "看客户组合",
                Map.of(), List.of(), new AnalysisChatRequest.ChatContext(Map.of(), "revenue",
                null, null, null, null, "经销", null, null));
        service.chat("analysis-id", session(), channelRequest);
        assertThat(prompt.get()).contains("\"groupBy\":\"customer\"", "\"channel\":1")
                .doesNotContain("经销", "客户乙", "汤圆");

        AnalysisChatRequest groupRequest = new AnalysisChatRequest("deepseek", "salesGroup", "看产品下钻",
                Map.of(), List.of(), new AnalysisChatRequest.ChatContext(Map.of(), "profit",
                null, "transferCost", "销售组", null, null, "B组", null));
        service.chat("analysis-id", session(), groupRequest);
        assertThat(prompt.get()).contains("\"groupBy\":\"product\"", "\"salesGroup\":1")
                .doesNotContain("B组", "客户乙", "汤圆");

        AnalysisChatRequest explicitGroupCustomerRequest = new AnalysisChatRequest(
                "deepseek", "salesGroup", "看这个销售组的客户情况", Map.of(), List.of(),
                new AnalysisChatRequest.ChatContext(Map.of(), "profit", null, "transferCost",
                        "销售组", null, null, "B组", null));
        service.chat("analysis-id", session(), explicitGroupCustomerRequest);
        assertThat(prompt.get()).contains("\"groupBy\":\"customer\"", "\"salesGroup\":1")
                .doesNotContain("B组", "客户乙", "汤圆");
    }

    @Test
    void customerMultiSelectionIsNotOverwrittenByTheLegacySingleCustomerFocus() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return "{\"answer\":\"所选客户已合并聚合。\","
                            + "\"evidenceIds\":[\"totals.revenue\"],\"suggestions\":[]}";
                });
        AnalysisChatRequest.ChatContext context = new AnalysisChatRequest.ChatContext(
                Map.of("customer", List.of("客户甲", "客户乙")), "profit",
                "outboundQuantity", "cost", null, "客户甲", null, null, null);

        service.chat("analysis-id", session(), new AnalysisChatRequest(
                "deepseek", "customer", "合并查看所选客户的产品利润",
                Map.of(), List.of(), context));

        assertThat(prompt.get()).contains("\"groupBy\":\"product\"", "\"customer\":2", "\"value\":1500.00")
                .doesNotContain("\"customer\":1", "客户甲", "客户乙", "水饺", "汤圆");
    }

    @Test
    void questionCanSelectASafeClassificationLensWithLabelsButNeverEntityNames() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return "{\"answer\":\"经营分析分类 138 的贡献更集中，建议先应用该分类筛选再看产品。\","
                            + "\"evidenceIds\":[\"group_1.revenue\"],"
                            + "\"suggestions\":[{\"type\":\"setFilter\",\"label\":\"只看 138 分类\","
                            + "\"dimension\":\"businessAnalysisCategory\",\"values\":[\"138\"],\"value\":null}]}";
                });
        AnalysisChatRequest request = new AnalysisChatRequest("deepseek", "product",
                "哪些经营分析分类销售额高但利润率低？", Map.of(), List.of(),
                new AnalysisChatRequest.ChatContext(Map.of(), "revenue", "outboundQuantity", "transferCost"));

        var response = service.chat("analysis-id", session(), request);

        assertThat(prompt.get()).contains("\"groupBy\":\"businessAnalysisCategory\"",
                        "\"groupLabel\":\"138\"", "\"groupLabel\":\"168以上\"")
                .doesNotContain("水饺", "汤圆", "客户甲", "客户乙", "A组", "B组");
        assertThat(response.suggestions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo("setFilter");
            assertThat(action.dimension()).isEqualTo("businessAnalysisCategory");
            assertThat(action.values()).containsExactly("138");
        });
    }

    @Test
    void desktopAssistantCanSuggestSchemaReviewWithoutChangingAnyMapping() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return """
                            {"answer":"建议先复核当前字段口径。","evidenceIds":[],"suggestions":[
                              {"type":"reviewSchema","label":"打开字段口径复核","dimension":null,"values":[],"value":null}
                            ]}
                            """;
                });
        AnalysisChatRequest request = new AnalysisChatRequest("deepseek", "product",
                "这次分析结果不对，检查一下字段口径", Map.of(), List.of(),
                new AnalysisChatRequest.ChatContext(Map.of(), "revenue", null, null), true);

        var response = service.chat("analysis-id", session(), request);

        assertThat(prompt.get()).contains("reviewSchema");
        assertThat(response.suggestions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo("reviewSchema");
            assertThat(action.dimension()).isNull();
            assertThat(action.values()).isEmpty();
            assertThat(action.value()).isNull();
        });
    }

    @Test
    void aiContextUsesTheSelectedMonthsAndNamesConvertedQuantityAsASumInPieces() {
        AnalysisSession session = session(List.of(
                datedChatRow(LocalDate.of(2026, 5, 1), "产品甲", "400", "40", "30"),
                datedChatRow(LocalDate.of(2026, 6, 1), "产品乙", "600", "100", "60")
        ));
        AtomicReference<String> prompt = new AtomicReference<>();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    prompt.set(user);
                    return "{\"answer\":\"当前期间应按换算只数口径复核。\","
                            + "\"evidenceIds\":[\"totals.quantity\",\"totals.revenue\"],\"suggestions\":[]}";
                });
        AnalysisChatRequest.ChatContext context = new AnalysisChatRequest.ChatContext(
                Map.of(), "revenue", "convertedQuantity", "cost", null,
                null, null, null, null, "cumulative", List.of("2026"), List.of("2026-06"));

        var response = service.chat("analysis-id", session,
                new AnalysisChatRequest("deepseek", "product", "核对当前期间数量", Map.of(), List.of(), context));

        assertThat(prompt.get()).contains("\"periodMode\":\"cumulative\"",
                "\"appliedYears\":[\"2026\"]", "\"appliedMonths\":[\"2026-06\"]",
                "\"metric\":\"convertedQuantity\"", "\"label\":\"换算只数\"",
                "\"distinctProductCount\":false", "\"value\":600.00", "\"value\":60.0000")
                .doesNotContain("\"value\":1000.00", "产品甲", "产品乙");
        assertThat(response.evidence()).extracting(item -> item.id())
                .containsExactly("totals.quantity", "totals.revenue");
        assertThat(response.evidence().get(0).display()).contains("换算只数求和", "单位为只", "60 只");
    }

    @Test
    void quantityQuestionFallsBackToConvertedSumDistinctProductCountAndSelectedPeriod() {
        AnalysisSession session = session(List.of(
                datedChatRow(LocalDate.of(2026, 5, 1), "产品甲", "400", "40", "30"),
                datedChatRow(LocalDate.of(2026, 6, 1), "产品乙", "600", "100", "60")
        ));
        AtomicInteger attempts = new AtomicInteger();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    attempts.incrementAndGet();
                    return "{\"answer\":\"换算只数是错误的999只\",\"evidenceIds\":[],\"suggestions\":[]}";
                });
        AnalysisChatRequest.ChatContext context = new AnalysisChatRequest.ChatContext(
                Map.of(), "revenue", "convertedQuantity", "cost", null,
                null, null, null, null, "month", List.of("2026"), List.of("2026-06"));

        var response = service.chat("analysis-id", session,
                new AnalysisChatRequest("deepseek", "product",
                        "为什么产品数量少，换算只数的口径和单位是什么？", Map.of(), List.of(), context));

        assertThat(attempts).hasValue(2);
        assertThat(response.answer())
                .contains("换算只数的求和", "单位为只", "去重后的产品种类", "不能互换",
                        "当前筛选后的本地聚合结果")
                .doesNotContain("999");
        assertThat(response.evidence()).extracting(item -> item.id())
                .containsExactly("scope.period", "totals.quantity", "totals.productCount", "group_1.quantity");
        assertThat(response.evidence().get(0).display()).contains("月度", "2026-06");
        assertThat(response.evidence().get(1).display()).contains("换算只数求和", "60 只");
        assertThat(response.evidence().get(2).display()).contains("产品数", "1");
        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void fallbackCanExplainRevenueCostProfitAndPeriodUsingOnlyVerifiedTotals() {
        AtomicInteger attempts = new AtomicInteger();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    attempts.incrementAndGet();
                    return "not-json";
                });

        var response = service.chat("analysis-id", session(),
                new AnalysisChatRequest("deepseek", "profit", "核对当前期间收入、成本和毛利润",
                        Map.of(), List.of(), new AnalysisChatRequest.ChatContext(
                                Map.of(), "profit", "outboundQuantity", "cost")));

        assertThat(attempts).hasValue(2);
        assertThat(response.answer()).contains("销售额证据", "成本口径", "利润率与覆盖证据",
                "当前选定的期间范围", "当前筛选后的本地聚合结果");
        assertThat(response.evidence()).extracting(item -> item.id())
                .containsExactly("scope.period", "totals.revenue", "totals.cost", "totals.profit",
                        "totals.margin", "totals.profitCoverage");
        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void transferCostFallbackUsesTransferComparableProfitEvidence() {
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> "not-json");

        var response = service.chat("analysis-id", session(),
                new AnalysisChatRequest("deepseek", "salesGroup", "核对调拨成本、毛利率和覆盖情况",
                        Map.of(), List.of(), new AnalysisChatRequest.ChatContext(
                                Map.of(), "profit", "outboundQuantity", "transferCost")));

        assertThat(response.answer()).contains("调拨成本口径", "可比覆盖证据", "利润率与覆盖证据")
                .doesNotContain("600", "1000");
        assertThat(response.evidence()).extracting(item -> item.id())
                .contains("scope.period", "totals.transferCost", "totals.transferProfitCoverage",
                        "totals.transferGrossProfit", "totals.transferGrossMargin",
                        "totals.confirmedGrossProfit");
        assertThat(response.evidence()).extracting(item -> item.display())
                .anyMatch(display -> display.contains("调拨成本"))
                .anyMatch(display -> display.contains("调拨口径毛利润"))
                .anyMatch(display -> display.contains("调拨口径毛利率"))
                .anyMatch(display -> display.contains("已确认成本口径毛利润"));
        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void doesNotUseFallbackWhenRepairCallNeverReturnsASecondModelResponse() {
        AtomicInteger attempts = new AtomicInteger();
        AnalysisChatService service = new AnalysisChatService(configuredProvider(), new ExploreService(),
                new ObjectMapper(), (secret, system, user, maxTokens, temperature) -> {
                    if (attempts.incrementAndGet() == 1) {
                        return "{\"answer\":\"虚构增长20%\",\"evidenceIds\":[],\"suggestions\":[]}";
                    }
                    throw new RuntimeException("network unavailable");
                });

        assertThatThrownBy(() -> service.chat("analysis-id", session(),
                new AnalysisChatRequest("deepseek", "product", "预测一下", Map.of(), List.of(), null)))
                .isInstanceOf(AiInsightService.AiCallException.class)
                .hasMessageContaining("聚合证据与调整白名单校验")
                .hasMessageContaining("没有修改当前分析");
        assertThat(attempts).hasValue(2);
    }

    private ProviderConfigService configuredProvider() {
        ProviderConfigService provider = new ProviderConfigService(new CryptoService(tempDir.resolve("config")));
        provider.update("deepseek", new ProviderUpdate(null, null, "api-key", false));
        return provider;
    }

    private static AnalysisSession session() {
        List<DataRow> rows = List.of(
                row("水饺", "客户甲", "1000", "600", "10"),
                row("汤圆", "客户乙", "500", "400", "5"));
        return session(rows);
    }

    private static AnalysisSession session(List<DataRow> rows) {
        AnalysisService analysis = new AnalysisService();
        var result = analysis.analyze(new SpreadsheetImportService.ImportResult(rows, 1,
                new QualityReport(rows.size(), rows.size(), 0, 0, 0, 0, List.of())));
        return analysis.requireSession(result.id());
    }

    private static DataRow classificationRow(String category, String revenue) {
        return new DataRow("source.xlsx", null, "产品-" + category, "客户", bd("1"), bd(revenue),
                bd("50"), BigDecimal.ZERO,
                Map.of("businessAnalysisCategory", category),
                Set.of("revenue", "cost", "outboundQuantity"),
                Map.of("outboundQuantity", bd("1")));
    }

    private static DataRow row(String product, String customer, String revenue, String transferCost,
                               String quantity) {
        boolean first = "客户甲".equals(customer);
        return new DataRow("source.xlsx", null, product, customer, bd(quantity), bd(revenue), bd("700"),
                BigDecimal.ZERO,
                Map.of("region", "华东", "salesGroup", first ? "A组" : "B组",
                        "channel", first ? "直营" : "经销",
                        "businessAnalysisCategory", first ? "138" : "168以上"),
                Set.of("revenue", "cost", "transferCost", "outboundQuantity"),
                Map.of("transferCost", bd(transferCost), "outboundQuantity", bd(quantity)));
    }

    private static DataRow datedChatRow(LocalDate date, String product, String revenue,
                                        String outbound, String converted) {
        return new DataRow("source.xlsx", date, product, "客户", bd(outbound), bd(revenue), bd("300"),
                BigDecimal.ZERO, Map.of("businessAnalysisCategory", "分类"),
                Set.of("revenue", "cost", "quantity", "outboundQuantity", "convertedQuantity"),
                Map.of("outboundQuantity", bd(outbound), "convertedQuantity", bd(converted)));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
