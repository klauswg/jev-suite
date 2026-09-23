package com.jevsuite.rental.domain;

import java.util.List;

/** 一次看房确认清单的结果。 */
public record ChecklistReport(
        String runId,
        String status,                    // READY / INSUFFICIENT_INFO / NEEDS_HUMAN_REVIEW
        String city,
        Integer rent,
        Boolean rentOutlier,              // 租金偏离内置区间 ±40%（硬规则，不走 Jev）
        List<CheckItem> items,            // 按看房动线排序：产权→硬件→费用→合同→其他
        List<String> fluff,               // 被过滤的无效修饰（信息密度低提示）
        long inputTokens,
        boolean degraded
) {
    public record CheckItem(
            int claimNo,
            String claim,
            String claimClass,            // ON_SITE / NEED_EVIDENCE / HIGH_RISK
            String category,              // property_rights / hardware / fees / contract / other
            String riskReason,            // low_price_bait / vague_disclaimer / sublease_risk / deposit_trap / null
            String rawClass,              // 门控前的 Jev 裸判定（ON_SITE/NEED_EVIDENCE/HIGH_RISK），eval 对照用
            double confidence,
            String question               // 质询话术（代码模板生成，不让模型写）
    ) {}
}
