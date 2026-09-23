package com.jevsuite.fidelity.domain;

import java.util.List;

/** 一次保真检查的结果。 */
public record FidelityReport(
        String runId,
        String status,                      // FAITHFUL / NOT_FAITHFUL / NEEDS_HUMAN_REVIEW / UNPARSEABLE
        Integer fidelityBand,               // 1-5（Score 序数）
        List<FactVerdict> factVerdicts,
        List<NewContent> newContents,       // 编辑稿新增内容（未核实）
        long inputTokens,
        boolean degraded
) {
    public record FactVerdict(
            String factId,
            String originalSentence,
            String verdict,                 // PRESERVED / EQUIVALENT / DRIFT / LOST / SUSPECTED_LOST / REVIEW / NOT_FACT
            String jevChoice,               // Jev 原始 choice（无调用时为 null）
            double choiceConfidence,
            List<String> alignedEdited      // 对齐到的编辑稿候选句（锚点）
    ) {}

    public record NewContent(
            String sentence,
            Boolean needsSource,            // Noul ≥ 0.5 → true；null = 未判定（超出成本上限）
            double noul
    ) {}
}
