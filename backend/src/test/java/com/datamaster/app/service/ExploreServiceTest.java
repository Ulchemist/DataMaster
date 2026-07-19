package com.datamaster.app.service;

import com.datamaster.app.domain.AnalysisSession;
import com.datamaster.app.domain.DataRow;
import com.datamaster.app.domain.ExploreRequest;
import com.datamaster.app.domain.QualityReport;
import com.datamaster.app.support.AnalysisFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExploreServiceTest {
    private final ExploreService service = new ExploreService();

    @Test
    void keepsLegacyExploreJsonRequestsCompatible() throws Exception {
        String json = """
                {"groupBy":"customer","filters":{"销售组":["A组"]},
                 "quantityMetric":"outboundQuantity","costMetric":"transferCost",
                 "sortBy":"revenue","limit":100}
                """;

        ExploreRequest request = new ObjectMapper().readValue(json, ExploreRequest.class);

        assertThat(request.groupBy()).isEqualTo("customer");
        assertThat(request.filters()).containsEntry("销售组", List.of("A组"));
        assertThat(request.sortBy()).isEqualTo("revenue");
        assertThat(request.search()).isNull();
        assertThat(request.profitFilter()).isNull();
        assertThat(request.offset()).isNull();
        assertThat(request.limit()).isEqualTo(100);
        assertThat(request.wantsFilterOptions()).isTrue();
    }

    @Test
    void canSkipRepeatedFilterOptionExpansionForDisplayOnlyExplorers() throws Exception {
        AnalysisSession session = session(List.of(
                row("产品甲", "客户甲", "分类甲", "一组", "直营", "100", "60", "55", "10", "10"),
                row("产品乙", "客户乙", "分类乙", "二组", "经销", "200", "120", "110", "20", "20")
        ));
        ExploreRequest request = new ObjectMapper().readValue("""
                {"groupBy":"product","limit":20,"includeFilterOptions":false}
                """, ExploreRequest.class);

        var response = service.explore("analysis-no-filter-options", session, request);

        assertThat(response.items()).hasSize(2);
        assertThat(response.filterOptions()).isEmpty();
        assertThat(response.availableDimensions()).contains("product", "customer", "salesGroup");
    }

    @Test
    void completeFixtureExercisesNewDimensionsAndMeasures() {
        AnalysisService analysis = new AnalysisService();
        var fixture = AnalysisFixtures.completeAnalysis(analysis);

        var response = service.explore(fixture.id(), analysis.requireSession(fixture.id()),
                new ExploreRequest("销售组", Map.of(), "换算只数", "调拨成本", 10));

        assertThat(response.availableDimensions()).contains(
                "customerAnalysisCategory", "customerAnalysisLargeCategory",
                "businessAnalysisCategory", "businessAnalysisLargeCategory", "productForm",
                "region", "department", "salesGroup", "channel");
        assertThat(response.availableQuantityMetrics()).contains(
                "quantity", "outboundQuantity", "convertedQuantity");
        assertThat(response.availableCostMetrics()).contains("cost", "transferCost");
        assertThat(response.items()).hasSize(3);
    }

    @Test
    void appliesExplicitMonthSelectionEvenInCumulativeModeAndReturnsScopedHeadlineMetrics() {
        AnalysisSession session = session(List.of(
                datedRow(LocalDate.of(2026, 5, 15), "产品甲", "客户甲",
                        "400", "240", "20", "40", "30", "12"),
                datedRow(LocalDate.of(2026, 6, 15), "产品甲", "客户甲",
                        "600", "300", "30", "100", "60", "25")
        ));

        ExploreRequest request = new ExploreRequest("product", Map.of(), null, "cost",
                null, null, null, 0, 20, "cumulative", List.of("2026"), List.of("2026-06"));
        var response = service.explore("analysis-period", session, request);

        assertThat(response.periodMode()).isEqualTo("cumulative");
        assertThat(response.appliedYears()).containsExactly("2026");
        assertThat(response.appliedMonths()).containsExactly("2026-06");
        assertThat(response.availableYears()).containsExactly("2026");
        assertThat(response.availableMonths()).containsExactly("2026-05", "2026-06");
        assertThat(response.availableDimensions()).contains("year", "month");
        assertThat(response.quantityMetric()).isEqualTo("convertedQuantity");
        assertThat(response.totals().records()).isEqualTo(1);
        assertThat(response.totals().revenue()).isEqualByComparingTo("600.00");
        assertThat(response.totals().quantity()).isEqualByComparingTo("60.0000");
        assertThat(response.totals().salesQuantity()).isEqualByComparingTo("100.0000");
        assertThat(response.totals().outboundQuantity()).isEqualByComparingTo("100.0000");
        assertThat(response.totals().convertedQuantity()).isEqualByComparingTo("60.0000");
        assertThat(response.totals().unitPrice()).isEqualByComparingTo("10.0000");
        assertThat(response.totals().averageCost()).isEqualByComparingTo("5.0000");
        assertThat(response.totals().unitGrossProfit()).isEqualByComparingTo("5.0000");
        assertThat(response.periodSummary().revenue()).isEqualByComparingTo("600.00");
        assertThat(response.periodSummary().grossProfit()).isEqualByComparingTo("300.00");
        assertThat(response.periodSummary().operatingProfit()).isEqualByComparingTo("270.00");
        assertThat(response.periodSummary().revenueAvailable()).isTrue();
        assertThat(response.periodSummary().costAvailable()).isTrue();
        assertThat(response.periodSummary().expenseAvailable()).isTrue();
        assertThat(response.periodSummary().quantityAvailable()).isTrue();
        assertThat(response.periodSummary().grossComparableRows()).isEqualTo(1);
        assertThat(response.periodSummary().operatingComparableRows()).isEqualTo(1);
        assertThat(response.periodSummary().grossProfitExcludedRows()).isZero();
        assertThat(response.periodSummary().operatingProfitExcludedRows()).isZero();
    }

    @Test
    void keepsOutboundAndConvertedQuantityDefinitionsSeparateAndSupportsMonthlyComparison() {
        AnalysisSession session = session(List.of(
                datedRow(LocalDate.of(2026, 5, 15), "产品甲", "客户甲",
                        "400", "240", "20", "40", "30", "12"),
                datedRow(LocalDate.of(2026, 6, 15), "产品乙", "客户乙",
                        "600", "300", "30", "100", "60", "25")
        ));

        var outbound = service.explore("analysis-outbound", session,
                new ExploreRequest("month", Map.of(), "outboundQuantity", "cost",
                        null, null, null, 0, 20, "month", List.of(),
                        List.of("2026-05", "2026-06")));
        var converted = service.explore("analysis-converted", session,
                new ExploreRequest("month", Map.of(), "convertedQuantity", "cost",
                        null, null, null, 0, 20, "month", List.of(),
                        List.of("2026-05", "2026-06")));

        assertThat(outbound.items()).extracting(item -> item.name())
                .containsExactlyInAnyOrder("2026-05", "2026-06");
        assertThat(outbound.totals().quantity()).isEqualByComparingTo("140.0000");
        assertThat(outbound.totals().outboundQuantity()).isEqualByComparingTo("140.0000");
        assertThat(outbound.totals().convertedQuantity()).isEqualByComparingTo("90.0000");
        assertThat(converted.totals().quantity()).isEqualByComparingTo("90.0000");
        assertThat(converted.totals().outboundQuantity()).isEqualByComparingTo("140.0000");
        assertThat(converted.totals().convertedQuantity()).isEqualByComparingTo("90.0000");
    }

    @Test
    void slicesBusinessCategoriesAndReturnsProductCountRevenueShareAndSelectableMeasures() {
        AnalysisSession session = session(List.of(
                row("水饺", "客户甲", "速冻", "A组", "直营", "1000", "700", "600", "10", "120"),
                row("汤圆", "客户甲", "速冻", "A组", "直营", "500", "360", "300", "5", "60"),
                row("面点", "客户乙", "面食", "B组", "经销", "500", "350", "400", "4", "48")
        ));

        var response = service.explore("analysis-1", session, new ExploreRequest(
                "businessAnalysisLargeCategory", Map.of(), "convertedQuantity", "transferCost", 20));

        assertThat(response.availableDimensions()).contains(
                "businessAnalysisLargeCategory", "salesGroup", "channel");
        assertThat(response.availableQuantityMetrics()).containsExactly(
                "convertedQuantity", "outboundQuantity");
        assertThat(response.availableCostMetrics()).containsExactly("cost", "transferCost");
        assertThat(response.totals().revenue()).isEqualByComparingTo("2000.00");
        assertThat(response.totals().cost()).isEqualByComparingTo("1300.00");
        assertThat(response.totals().profit()).isEqualByComparingTo("700.00");
        assertThat(response.totals().quantity()).isEqualByComparingTo("228.0000");
        assertThat(response.totals().outboundQuantity()).isEqualByComparingTo("19.0000");
        assertThat(response.totals().convertedQuantity()).isEqualByComparingTo("228.0000");
        assertThat(response.totals().transferCost()).isEqualByComparingTo("1300.00");
        assertThat(response.totals().transferComparableRevenue()).isEqualByComparingTo("2000.00");
        assertThat(response.totals().transferComparableCost()).isEqualByComparingTo("1300.00");
        assertThat(response.totals().transferGrossProfit()).isEqualByComparingTo("700.00");
        assertThat(response.totals().transferGrossMargin()).isEqualByComparingTo("0.3500");
        assertThat(response.totals().transferProfitCoverage()).isEqualByComparingTo("1.0000");
        assertThat(response.totals().transferProfitAvailable()).isTrue();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).name()).isEqualTo("速冻");
        assertThat(response.items().get(0).productCount()).isEqualTo(2);
        assertThat(response.items().get(0).revenue()).isEqualByComparingTo("1500.00");
        assertThat(response.items().get(0).share()).isEqualByComparingTo("0.7500");
    }

    @Test
    void drillsFromCustomerToItsProductsAndSupportsOrganisationFilters() {
        AnalysisSession session = session(List.of(
                row("水饺", "客户甲", "速冻", "A组", "直营", "1000", "700", "600", "10", "120"),
                row("汤圆", "客户甲", "速冻", "A组", "直营", "500", "360", "300", "5", "60"),
                row("面点", "客户乙", "面食", "B组", "经销", "500", "350", "400", "4", "48")
        ));

        var response = service.explore("analysis-2", session, new ExploreRequest("产品",
                Map.of("客户", List.of("客户甲"), "销售组", List.of("A组")),
                "outboundQuantity", "transferCost", 10));

        assertThat(response.groupBy()).isEqualTo("product");
        assertThat(response.appliedFilters()).containsKeys("customer", "salesGroup");
        assertThat(response.filterOptions()).containsKeys("customer", "客户", "salesGroup", "销售组");
        assertThat(response.totals().records()).isEqualTo(2);
        assertThat(response.totals().customerCount()).isEqualTo(1);
        assertThat(response.items()).extracting(item -> item.name()).containsExactly("水饺", "汤圆");
        assertThat(response.items().get(0).profit()).isEqualByComparingTo("400.00");
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("100.0000");
    }

    @Test
    void keepsFullSalesSeparateFromComparableProfitBasis() {
        DataRow complete = row("水饺", "客户甲", "速冻", "A组", "直营",
                "100", "60", "60", "1", "1");
        DataRow missingCost = new DataRow("source.xlsx", null, "水饺", "客户甲", bd("1"),
                bd("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of("businessAnalysisLargeCategory", "速冻", "salesGroup", "A组", "channel", "直营"),
                Set.of("revenue", "outboundQuantity", "convertedQuantity"),
                Map.of("outboundQuantity", bd("1"), "convertedQuantity", bd("1")));
        AnalysisSession session = session(List.of(complete, missingCost));

        var response = service.explore("analysis-comparable", session,
                new ExploreRequest("product", Map.of(), null, "cost", 10));
        var product = response.items().get(0);

        assertThat(product.revenue()).isEqualByComparingTo("110.00");
        assertThat(product.comparableRevenue()).isEqualByComparingTo("100.00");
        assertThat(product.comparableCost()).isEqualByComparingTo("60.00");
        assertThat(product.profit()).isEqualByComparingTo("40.00");
        assertThat(product.profitCoverage()).isEqualByComparingTo("0.9091");
        assertThat(product.excludedProfitRows()).isEqualTo(1);
    }

    @Test
    void negativeComparableRevenueKeepsProfitButDoesNotPublishAMisleadingPositiveMargin() {
        AnalysisSession session = session(List.of(
                row("商业折扣", "客户甲", "调整", "A组", "直营", "-100", "-5", "-5", "1", "1")
        ));

        var response = service.explore("analysis-negative-revenue", session,
                new ExploreRequest("product", Map.of(), null, "cost", 10));
        var product = response.items().get(0);

        assertThat(product.profitAvailable()).isTrue();
        assertThat(product.comparableRevenue()).isEqualByComparingTo("-100.00");
        assertThat(product.profit()).isEqualByComparingTo("-95.00");
        assertThat(product.margin()).isNull();
    }

    @Test
    void ranksProductSlicesByTheRequestedQuantityOrComparableUnitPrice() {
        AnalysisSession session = session(List.of(
                row("高销量产品", "客户甲", "速冻", "A组", "直营", "100", "60", "50", "100", "100"),
                row("高单价产品", "客户乙", "面食", "B组", "经销", "1000", "600", "500", "1", "1")
        ));

        var byQuantity = service.explore("analysis-quantity", session, new ExploreRequest(
                "product", Map.of(), "outboundQuantity", "cost", "quantity", 1));
        var byUnitPrice = service.explore("analysis-unit-price", session, new ExploreRequest(
                "product", Map.of(), "outboundQuantity", "cost", "unitPrice", 1));

        assertThat(byQuantity.totalGroups()).isEqualTo(2);
        assertThat(byQuantity.truncated()).isTrue();
        assertThat(byQuantity.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("高销量产品");
            assertThat(item.quantity()).isEqualByComparingTo("100.0000");
            assertThat(item.unitPrice()).isEqualByComparingTo("1.0000");
        });
        assertThat(byUnitPrice.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("高单价产品");
            assertThat(item.unitPrice()).isEqualByComparingTo("1000.0000");
        });
    }

    @Test
    void fuzzySearchesGroupedCustomerOrProductNamesWithoutChangingFilteredTotals() {
        AnalysisSession session = session(List.of(
                row("Alpha 水饺", "华东客户甲", "速冻", "A组", "直营", "100", "60", "50", "10", "120"),
                row("Beta 汤圆", "华南客户乙", "速冻", "B组", "经销", "200", "120", "100", "20", "240"),
                row("Gamma 面点", "华南客户丙", "面食", "B组", "经销", "300", "180", "150", "30", "360")
        ));

        var customers = service.explore("analysis-search-customer", session, new ExploreRequest(
                "customer", Map.of(), null, "transferCost", "revenue", "华南", 0, 20));
        var products = service.explore("analysis-search-product", session, new ExploreRequest(
                "product", Map.of(), null, "transferCost", "revenue", "alpha", 0, 20));

        assertThat(customers.search()).isEqualTo("华南");
        assertThat(customers.totalGroups()).isEqualTo(2);
        assertThat(customers.items()).extracting(item -> item.name())
                .containsExactly("华南客户丙", "华南客户乙");
        assertThat(customers.totals().revenue()).isEqualByComparingTo("600.00");
        assertThat(products.items()).extracting(item -> item.name()).containsExactly("Alpha 水饺");
    }

    @Test
    void fuzzyProductSearchUsesNfkcIgnoresCaseAndAllUnicodeWhitespace() {
        AnalysisSession session = session(List.of(
                row("Ａｌｐｈａ　 水\u2003饺", "客户甲", "速冻", "A组", "直营",
                        "100", "60", "50", "10", "120"),
                row("Beta 汤圆", "客户乙", "速冻", "B组", "经销",
                        "200", "120", "100", "20", "240")
        ));

        var products = service.explore("analysis-normalized-product-search", session,
                new ExploreRequest("product", Map.of(), null, "transferCost", "revenue",
                        " aLpHa\u00a0水　饺 ", 0, 20));

        assertThat(products.items()).extracting(item -> item.name())
                .containsExactly("Ａｌｐｈａ　 水\u2003饺");
        assertThat(products.totalGroups()).isEqualTo(1);
    }

    @Test
    void fuzzyCustomerSearchUsesNfkcIgnoresCaseAndAllUnicodeWhitespace() {
        AnalysisSession session = session(List.of(
                row("产品甲", "华\u2003南　客户Ａ", "速冻", "A组", "直营",
                        "100", "60", "50", "10", "120"),
                row("产品乙", "华北客户B", "速冻", "B组", "经销",
                        "200", "120", "100", "20", "240")
        ));

        var customers = service.explore("analysis-normalized-customer-search", session,
                new ExploreRequest("customer", Map.of(), null, "transferCost", "revenue",
                        " 华 南 客 户 a ", 0, 20));

        assertThat(customers.items()).extracting(item -> item.name())
                .containsExactly("华\u2003南　客户Ａ");
        assertThat(customers.totalGroups()).isEqualTo(1);
    }

    @Test
    void paginatesEveryBusinessDimensionWithAStableBoundedContract() {
        AnalysisSession session = session(List.of(
                row("产品甲", "客户甲", "分类甲", "A组", "直营", "400", "200", "180", "4", "40"),
                row("产品乙", "客户乙", "分类乙", "B组", "经销", "300", "150", "140", "3", "30"),
                row("产品丙", "客户丙", "分类丙", "C组", "线上", "200", "100", "90", "2", "20"),
                row("产品丁", "客户丁", "分类丁", "D组", "直营", "100", "50", "45", "1", "10")
        ));

        var first = service.explore("analysis-page-1", session, new ExploreRequest(
                "businessAnalysisLargeCategory", Map.of(), null, "transferCost", "revenue", null, 0, 2));
        var second = service.explore("analysis-page-2", session, new ExploreRequest(
                "businessAnalysisLargeCategory", Map.of(), null, "transferCost", "revenue", null, 2, 2));

        assertThat(first.offset()).isZero();
        assertThat(first.pageSize()).isEqualTo(2);
        assertThat(first.returnedGroups()).isEqualTo(2);
        assertThat(first.totalGroups()).isEqualTo(4);
        assertThat(first.truncated()).isTrue();
        assertThat(first.hasMore()).isTrue();
        assertThat(second.offset()).isEqualTo(2);
        assertThat(second.returnedGroups()).isEqualTo(2);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.items()).extracting(item -> item.name()).containsExactly("分类甲", "分类乙");
        assertThat(second.items()).extracting(item -> item.name()).containsExactly("分类丙", "分类丁");
    }

    @Test
    void filtersLossMakingCustomersBeforePaginationSoLowRevenueLossesAreNotMissed() {
        DataRow unavailable = new DataRow("source.xlsx", null, "产品丁", "成本缺失客户", bd("1"),
                bd("2000"), BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of("businessAnalysisLargeCategory", "分类丁", "salesGroup", "D组",
                        "channel", "直营", "region", "华东", "department", "销售一部"),
                Set.of("revenue", "outboundQuantity"), Map.of("outboundQuantity", bd("1")));
        AnalysisSession session = session(List.of(
                row("产品甲", "盈利大客户", "分类甲", "A组", "直营", "1000", "500", "500", "10", "100"),
                row("产品乙", "亏损客户甲", "分类乙", "B组", "经销", "20", "30", "30", "2", "20"),
                row("产品丙", "亏损客户乙", "分类丙", "C组", "线上", "10", "25", "25", "1", "10"),
                unavailable
        ));

        var firstLoss = service.explore("analysis-loss", session, new ExploreRequest(
                "customer", Map.of(), null, "cost", "profit", null, "loss", 0, 1));

        assertThat(firstLoss.profitFilter()).isEqualTo("loss");
        assertThat(firstLoss.totalGroups()).isEqualTo(2);
        assertThat(firstLoss.returnedGroups()).isEqualTo(1);
        assertThat(firstLoss.hasMore()).isTrue();
        assertThat(firstLoss.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("亏损客户乙");
            assertThat(item.profit()).isEqualByComparingTo("-15.00");
        });

        var allByProfit = service.explore("analysis-profit-sort", session, new ExploreRequest(
                "customer", Map.of(), null, "cost", "profit", 10));
        assertThat(allByProfit.items()).extracting(item -> item.name()).containsExactly(
                "亏损客户乙", "亏损客户甲", "盈利大客户", "成本缺失客户");
        assertThat(allByProfit.items().get(3).profitAvailable()).isFalse();
    }

    @Test
    void rejectsUnknownDimensionsAndMetricsInsteadOfSilentlyChangingDefinitions() {
        AnalysisSession session = session(List.of(
                row("水饺", "客户甲", "速冻", "A组", "直营", "1000", "700", "600", "10", "120")));

        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("unknown", Map.of(), null, null, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分组字段");
        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("product", Map.of(), "fakeQuantity", null, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("数量口径");
        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("product", Map.of(), null, null, "fakeSort", 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("排序指标");
        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("product", Map.of(), null, null, "revenue", null, -1, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("offset");
        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("product", Map.of(), null, null, "revenue", null, 0, 501)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.explore("id", session,
                new ExploreRequest("customer", Map.of(), null, "cost", "revenue", null,
                        "unknown", 0, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profitFilter");
    }

    @Test
    void comparesSalesDepartmentsWithTransferCostProfitAndQuantityMetrics() {
        AnalysisSession session = session(List.of(
                organisationRow("销售一部", "1000", "900", "600", "100", "80"),
                organisationRow("销售二部", "800", "500", "700", "70", "50")
        ));

        var response = service.explore("department-transfer", session,
                new ExploreRequest("department", Map.of(), "convertedQuantity", "transferCost", 20));

        assertThat(response.costMetric()).isEqualTo("transferCost");
        assertThat(response.items()).extracting(item -> item.name())
                .containsExactly("销售一部", "销售二部");
        assertThat(response.items().get(0).quantity()).isEqualByComparingTo("80.0000");
        assertThat(response.items().get(0).cost()).isEqualByComparingTo("600.00");
        assertThat(response.items().get(0).profit()).isEqualByComparingTo("400.00");
        assertThat(response.items().get(0).margin()).isEqualByComparingTo("0.4000");
        assertThat(response.items().get(0).transferGrossProfit()).isEqualByComparingTo("400.00");
    }

    @Test
    void publishesPartiallyCoveredTransferProfitInsteadOfErasingTheComparableResult() {
        DataRow withTransferCost = organisationRow("拓展部", "700", "500", "560", "70", "50");
        DataRow withoutTransferCost = new DataRow("source.xlsx", LocalDate.of(2026, 6, 2),
                "产品乙", "客户乙", bd("30"), bd("300"), bd("210"), BigDecimal.ZERO,
                Map.of("region", "华东", "department", "拓展部", "salesGroup", "拓展一组"),
                Set.of("revenue", "cost", "quantity", "outboundQuantity", "convertedQuantity"),
                Map.of("outboundQuantity", bd("30"), "convertedQuantity", bd("20")));
        AnalysisSession session = session(List.of(withTransferCost, withoutTransferCost));

        var response = service.explore("partial-transfer", session,
                new ExploreRequest("department", Map.of(), "convertedQuantity", "transferCost", 20));
        var department = response.items().get(0);

        assertThat(department.transferProfitCoverage()).isEqualByComparingTo("0.7000");
        assertThat(department.transferProfitAvailable()).isTrue();
        assertThat(department.transferComparableRevenue()).isEqualByComparingTo("700.00");
        assertThat(department.transferComparableCost()).isEqualByComparingTo("560.00");
        assertThat(department.transferGrossProfit()).isEqualByComparingTo("140.00");
        assertThat(department.profit()).isEqualByComparingTo("140.00");
        assertThat(department.confirmedProfitAvailable()).isTrue();
        assertThat(department.confirmedComparableRevenue()).isEqualByComparingTo("1000.00");
        assertThat(department.confirmedComparableCost()).isEqualByComparingTo("710.00");
        assertThat(department.confirmedGrossProfit()).isEqualByComparingTo("290.00");
        assertThat(department.confirmedProfitCoverage()).isEqualByComparingTo("1.0000");
    }

    @Test
    void keepsConfirmedCostComparisonWhenAGroupHasNoTransferCost() {
        DataRow row = new DataRow("source.xlsx", LocalDate.of(2026, 6, 1),
                "产品", "客户", bd("10"), bd("100"), bd("75"), BigDecimal.ZERO,
                Map.of("region", "华东", "department", "营销中心办公室", "salesGroup", "营销组"),
                Set.of("revenue", "cost", "quantity", "outboundQuantity", "convertedQuantity"),
                Map.of("outboundQuantity", bd("10"), "convertedQuantity", bd("8")));
        AnalysisSession session = session(List.of(row));

        var response = service.explore("missing-transfer", session,
                new ExploreRequest("department", Map.of(), "convertedQuantity", "cost", 20));
        var department = response.items().get(0);

        assertThat(department.transferProfitAvailable()).isFalse();
        assertThat(department.transferGrossProfit()).isNull();
        assertThat(department.confirmedProfitAvailable()).isTrue();
        assertThat(department.confirmedGrossProfit()).isEqualByComparingTo("25.00");
        assertThat(department.confirmedGrossMargin()).isEqualByComparingTo("0.2500");
        assertThat(department.confirmedProfitCoverage()).isEqualByComparingTo("1.0000");
    }

    @Test
    void exposesAllDistinctSalesGroupsForCustomerAggregates() {
        AnalysisSession session = session(List.of(
                row("产品甲", "同一客户", "分类", "盒马组", "直营", "100", "60", "55", "10", "10"),
                row("产品乙", "同一客户", "分类", "天虹组", "直营", "200", "120", "110", "20", "20"),
                row("产品丙", "同一客户", "分类", "盒马组", "直营", "300", "180", "165", "30", "30")
        ));

        var response = service.explore("customer-sales-groups", session,
                new ExploreRequest("customer", Map.of(), null, "cost", 20));

        assertThat(response.items()).singleElement().satisfies(customer ->
                assertThat(customer.salesGroups()).containsExactly("天虹组", "盒马组"));
    }

    @Test
    void publishesModalProductSpecAlongsideProductAggregates() {
        AnalysisSession session = session(List.of(
                specRow("清远鸡（盒）", "400g/整只/气调盒/鲜品", "100", "60", "50"),
                specRow("清远鸡（盒）", "400g/整只/气调盒/鲜品", "100", "60", "50"),
                specRow("清远鸡（盒）", "简袋/鲜品", "50", "30", "25"),
                specRow("土香鸡", "简袋/鲜品", "80", "50", "40")
        ));

        var response = service.explore("analysis-spec", session, new ExploreRequest(
                "product", Map.of(), null, "transferCost", "revenue", null, null, 20));

        assertThat(response.items()).extracting(item -> item.name())
                .containsExactly("清远鸡（盒）", "土香鸡");
        assertThat(response.items().get(0).spec()).isEqualTo("400g/整只/气调盒/鲜品");
        assertThat(response.items().get(0).specVariants())
                .containsExactly("400g/整只/气调盒/鲜品", "简袋/鲜品");
        assertThat(response.items().get(1).spec()).isEqualTo("简袋/鲜品");
        assertThat(response.items().get(1).specVariants()).containsExactly("简袋/鲜品");
        assertThat(response.availableDimensions()).contains("productSpec");
    }

    @Test
    void discoversDrillPathsFromDataInsteadOfHardcodingThem() {
        List<DataRow> rows = new ArrayList<>();
        String[][] blueprint = {
                {"华东", "销售一部", "A组", "福州公司", "福州市台江区"},
                {"华东", "销售一部", "A组", "福州公司", "福州市仓山区"},
                {"华东", "销售一部", "B组", "厦门公司", "厦门市思明区"},
                {"华南", "销售二部", "C组", "广州公司", "广州市天河区"},
                {"华南", "销售二部", "D组", "深圳公司", "深圳市南山区"},
                {"华南", "销售二部", "D组", "深圳公司", "深圳市宝安区"}
        };
        for (int copy = 0; copy < 6; copy++) {
            for (String[] entry : blueprint) rows.add(drillRow(entry));
        }
        AnalysisSession session = session(rows);

        var response = service.explore("analysis-drill", session, new ExploreRequest(
                "department", Map.of(), null, "cost", "revenue", null, null, 20));

        assertThat(response.drillSuggestions())
                .containsEntry("region", "salesGroup")
                .containsEntry("department", "salesGroup")
                .containsEntry("salesGroup", "deliveryAddress")
                .containsEntry("customerDetail", "deliveryAddress")
                .doesNotContainKey("deliveryAddress");
    }

    private static DataRow drillRow(String[] entry) {
        return new DataRow("source.xlsx", null, "产品", "客户", BigDecimal.ONE, bd("100"), bd("60"),
                BigDecimal.ZERO,
                Map.of("region", entry[0], "department", entry[1], "salesGroup", entry[2],
                        "customerDetail", entry[3], "deliveryAddress", entry[4]),
                Set.of("revenue", "cost"),
                Map.of());
    }

    private static DataRow specRow(String product, String spec, String revenue, String cost,
                                   String transferCost) {
        return new DataRow("source.xlsx", null, product, "客户", BigDecimal.ONE, bd(revenue), bd(cost),
                BigDecimal.ZERO,
                Map.of("productSpec", spec, "salesGroup", "一组", "region", "华东", "department", "销售一部"),
                Set.of("revenue", "cost", "transferCost"),
                Map.of("transferCost", bd(transferCost)));
    }

    private static AnalysisSession session(List<DataRow> rows) {
        AnalysisService analysis = new AnalysisService();
        var result = analysis.analyze(new SpreadsheetImportService.ImportResult(rows, 1,
                new QualityReport(rows.size(), rows.size(), 0, 0, 0, 0, List.of())));
        return analysis.requireSession(result.id());
    }

    private static DataRow row(String product, String customer, String category, String salesGroup,
                               String channel, String revenue, String cost, String transferCost,
                               String outbound, String converted) {
        return new DataRow("source.xlsx", null, product, customer, bd(outbound), bd(revenue), bd(cost),
                BigDecimal.ZERO,
                Map.of("businessAnalysisLargeCategory", category, "salesGroup", salesGroup,
                        "channel", channel, "region", "华东", "department", "销售一部"),
                Set.of("revenue", "cost", "transferCost", "outboundQuantity", "convertedQuantity"),
                Map.of("transferCost", bd(transferCost), "outboundQuantity", bd(outbound),
                        "convertedQuantity", bd(converted)));
    }

    private static DataRow datedRow(LocalDate date, String product, String customer,
                                    String revenue, String cost, String expense,
                                    String outbound, String converted, String transferCost) {
        return new DataRow("source.xlsx", date, product, customer, bd(outbound), bd(revenue), bd(cost),
                bd(expense),
                Map.of("businessAnalysisLargeCategory", "分类", "salesGroup", "一组",
                        "channel", "直营", "region", "华东", "department", "销售一部"),
                Set.of("revenue", "cost", "expense", "quantity", "transferCost",
                        "outboundQuantity", "convertedQuantity"),
                Map.of("transferCost", bd(transferCost), "outboundQuantity", bd(outbound),
                        "convertedQuantity", bd(converted)));
    }

    private static DataRow organisationRow(String department, String revenue, String cost,
                                           String transferCost, String outbound, String converted) {
        return new DataRow("source.xlsx", LocalDate.of(2026, 6, 1), "产品", "客户", bd(outbound),
                bd(revenue), bd(cost), BigDecimal.ZERO,
                Map.of("region", "华东", "department", department, "salesGroup", department + "一组"),
                Set.of("revenue", "cost", "quantity", "transferCost", "outboundQuantity",
                        "convertedQuantity"),
                Map.of("transferCost", bd(transferCost), "outboundQuantity", bd(outbound),
                        "convertedQuantity", bd(converted)));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
