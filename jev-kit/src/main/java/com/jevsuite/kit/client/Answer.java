package com.jevsuite.kit.client;

/**
 * 单个问题的类型化答案（泛化自 jev-guard 的固定四问模型）。
 * 实测事实（jev-guard docs/05-m1-findings.md）：
 *  - noul 无 confidence → 客户端派生 |v-0.5|*2
 *  - score 的 probabilities 是 0 基索引
 */
public sealed interface Answer {

    /** choice：选中项 + 上游信度（可空，防御上游变动） */
    record ChoiceAnswer(String choice, Double confidence) implements Answer {}

    /** score：0 基索引（原样保留）+ 档位总数 + 上游信度 */
    record ScoreAnswer(int zeroBasedIndex, int scaleSize, Double confidence) implements Answer {
        /** 1 基序数（1..scaleSize），门控一般用这个 */
        public int ordinal() { return zeroBasedIndex + 1; }
    }

    /** noul：0-1 概率 + 派生信度 |v-0.5|*2（上游不返回，实测） */
    record NoulAnswer(double value, double derivedConfidence) implements Answer {
        public static NoulAnswer of(double v) {
            return new NoulAnswer(v, Math.abs(v - 0.5) * 2.0);
        }
    }
}
