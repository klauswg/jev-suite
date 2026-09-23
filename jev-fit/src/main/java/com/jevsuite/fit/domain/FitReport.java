package com.jevsuite.fit.domain;

import java.util.List;

/** 一次差距分析的结果。 */
public record FitReport(
        String runId,
        String status,                       // STRONG_MATCH / APPLY_WITH_GAPS / BLOCKED_BY_MUST_HAVE / NEEDS_HUMAN_REVIEW
        Integer fitBand,                     // 1-5（Score 序数，不出百分制）
        List<RequirementVerdict> verdicts,
        long inputTokens,
        boolean degraded
) {
    public record RequirementVerdict(
            String requirementId,
            String requirementText,
            boolean mustHave,
            String verdict,                  // SATISFIED / GAP / UNCERTAIN
            double noul,
            double confidence,
            String gapType,                  // satisfied / hard_skill_missing / insufficient_experience / phrasing_mismatch / overqualified
            boolean rewriteEligible,         // 表述不匹配 + noul ≥ 0.6 → 触发改写建议（审批制，只标锚点不生成）
            List<EvidenceRef> evidence
    ) {}

    public record EvidenceRef(int lineNo, String text) {}
}
