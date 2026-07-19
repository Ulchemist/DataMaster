package com.datamaster.app.service;

import com.datamaster.app.domain.Breakdown;
import com.datamaster.app.domain.FinancialSummary;
import com.datamaster.app.domain.Insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class OfflineInsightService {
    private OfflineInsightService() {
    }

    public static List<Insight> generate(FinancialSummary summary, List<Breakdown> products,
                                         List<Breakdown> customers) {
        List<Insight> insights = new ArrayList<>();
        if (summary.operatingProfit().signum() < 0) {
            insights.add(new Insight(
                    "优先止住经营亏损",
                    "当前经营利润为负，现有毛利无法覆盖期间费用。",
                    "以产品为单位建立两周止损清单：暂停负贡献促销，复核采购价与折扣，并设定每单最低贡献毛利。",
                    "经营利润 " + money(summary.operatingProfit()) + "，经营利润率 " + percent(summary.operatingMargin())
            ));
        } else {
            insights.add(new Insight(
                    "守住盈利质量",
                    "当前经营结果为盈利，可继续关注利润率而不只追求收入增长。",
                    "按月跟踪毛利率和费用率，给异常波动设置复盘阈值。",
                    "经营利润 " + money(summary.operatingProfit()) + "，经营利润率 " + percent(summary.operatingMargin())
            ));
        }

        products.stream().min(Comparator.comparing(Breakdown::operatingProfit)).ifPresent(worst -> {
            if (worst.operatingProfit().signum() < 0) {
                insights.add(new Insight(
                        "治理亏损产品「" + worst.name() + "」",
                        "该产品是当前产品组合中经营利润最低的一项。",
                        "拆分售价、单位成本、促销折扣和退货损耗；先验证提价、降本或缩减低毛利订单三种情景。",
                        "产品经营利润 " + money(worst.operatingProfit()) + "，毛利率 " + percent(worst.grossMargin())
                ));
            }
        });

        if (summary.operatingComparableRevenue() != null
                && summary.operatingComparableExpenses() != null
                && summary.operatingComparableRevenue().signum() > 0) {
            BigDecimal expenseRate = summary.operatingComparableExpenses()
                    .divide(summary.operatingComparableRevenue(), 4, RoundingMode.HALF_UP);
            if (expenseRate.compareTo(new BigDecimal("0.15")) > 0) {
                insights.add(new Insight(
                    "压降期间费用率",
                    "经营可比行的期间费用率偏高，正在显著侵蚀经营利润。",
                    "将费用按固定、可变和一次性分类；优先取消无转化支出，并给获客费用设置回收期。",
                    "经营可比费用 " + money(summary.operatingComparableExpenses())
                            + "，经营可比费用率 " + percent(expenseRate)
                ));
            }
        }

        if (!customers.isEmpty() && summary.revenue().signum() > 0) {
            Breakdown top = customers.get(0);
            BigDecimal concentration = top.revenue().divide(summary.revenue(), 4, RoundingMode.HALF_UP);
            if (concentration.compareTo(new BigDecimal("0.40")) > 0) {
                insights.add(new Insight(
                        "降低客户集中风险",
                        "最大客户收入占比较高，单一客户波动可能影响整体经营结果。",
                        "保留核心客户服务，同时建立第二梯队客户拓展清单，逐月降低单客依赖。",
                        "最大客户「" + top.name() + "」收入占比 " + percent(concentration)
                ));
            }
        }
        return List.copyOf(insights.subList(0, Math.min(4, insights.size())));
    }

    private static String money(BigDecimal value) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.CHINA);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static String percent(BigDecimal value) {
        if (value == null) return "不适用";
        return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }
}
