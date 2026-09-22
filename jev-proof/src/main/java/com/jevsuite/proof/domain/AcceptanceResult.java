package com.jevsuite.proof.domain;

import java.util.List;

/** 一次验收运行的结果。 */
public record AcceptanceResult(
        String runId,
        String videoUrl,
        String status,              // PASS / NEEDS_FIX / HUMAN_REVIEW / UNVERIFIABLE
        String disclosure,          // compliant / misplaced / missing / unknown
        Integer qualityOrdinal,     // 1-5（Score 序数）
        List<PointVerdict> pointVerdicts,
        long inputTokens,
        boolean degraded
) {
    public record PointVerdict(
            String pointId,
            String verdict,         // PASS / FAIL / REVIEW（kit NoulGate）
            double noul,
            double confidence,
            List<Evidence> evidence
    ) {}

    public record Evidence(double startSec, double endSec, String text) {}
}
