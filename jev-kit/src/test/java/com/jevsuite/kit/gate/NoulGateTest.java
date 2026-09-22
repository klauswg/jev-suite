package com.jevsuite.kit.gate;

import com.jevsuite.kit.client.Answer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoulGateTest {

    private final NoulGate gate = new NoulGate(0.80, 0.20, 0.70);

    @Test
    void passOnlyWhenValueAndConfidenceBothHigh() {
        // 0.95 → conf=0.9 ≥0.7 → PASS
        assertEquals(NoulGate.Verdict.PASS, gate.judge(Answer.NoulAnswer.of(0.95)));
        // 0.81 → conf=0.62 <0.7 → REVIEW（信度不足不通过）
        assertEquals(NoulGate.Verdict.REVIEW, gate.judge(Answer.NoulAnswer.of(0.81)));
    }

    @Test
    void failBelowLow() {
        assertEquals(NoulGate.Verdict.FAIL, gate.judge(Answer.NoulAnswer.of(0.05)));
        assertEquals(NoulGate.Verdict.FAIL, gate.judge(Answer.NoulAnswer.of(0.20)));
    }

    @Test
    void middleGoesToReviewNeverDefaults() {
        assertEquals(NoulGate.Verdict.REVIEW, gate.judge(Answer.NoulAnswer.of(0.5)));
        assertEquals(NoulGate.Verdict.REVIEW, gate.judge(Answer.NoulAnswer.of(0.79)));
        assertEquals(NoulGate.Verdict.REVIEW, gate.judge(Answer.NoulAnswer.of(0.21)));
    }
}
