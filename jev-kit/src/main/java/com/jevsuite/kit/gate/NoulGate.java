package com.jevsuite.kit.gate;

import com.jevsuite.kit.client.Answer;

/**
 * 通用 Noul 三态门控（jev-guard 门控的泛化）：
 * value ≥ pHigh 且派生信度 ≥ cHigh → PASS
 * value ≤ pLow → FAIL
 * 其余（含信度不足）→ REVIEW（最保守桶，绝不默认通过）
 */
public class NoulGate {

    public enum Verdict { PASS, FAIL, REVIEW }

    private final double pHigh, pLow, cHigh;

    public NoulGate(double pHigh, double pLow, double cHigh) {
        this.pHigh = pHigh; this.pLow = pLow; this.cHigh = cHigh;
    }

    public Verdict judge(Answer.NoulAnswer a) {
        if (a.value() >= pHigh && a.derivedConfidence() >= cHigh) return Verdict.PASS;
        if (a.value() <= pLow) return Verdict.FAIL;
        return Verdict.REVIEW;
    }
}
