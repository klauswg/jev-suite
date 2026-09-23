package com.jevsuite.fidelity.service;

import com.jevsuite.fidelity.domain.FidelityReport;
import com.jevsuite.kit.client.MockJevClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FidelityServiceTest {

    @Test
    void noCandidateYieldsSuspectedLostWithoutModelCall() {
        FidelityService svc = new FidelityService(new MockJevClient(Map.of("edited_candidate[", 0.9), 0.2));
        FidelityReport r = svc.checkFacts(
                List.of("The company filed for bankruptcy last Tuesday."),
                List.of("Our team launched a brand new design system yesterday."));
        assertEquals(1, r.factVerdicts().size());
        assertEquals("SUSPECTED_LOST", r.factVerdicts().get(0).verdict());
        assertEquals(0, r.inputTokens());   // 无候选不调模型
        assertEquals("NEEDS_HUMAN_REVIEW", r.status());
    }

    @Test
    void chineseBigramAlignmentFindsCandidate() {
        List<String> keys = FidelityService.keysOf("公司营收增长了40%");
        assertTrue(keys.contains("公司") && keys.contains("营收"));
        List<String> hits = FidelityService.roughMatch("公司营收增长了40%",
                List.of("公司营收同比增长四成以上。", "完全无关的另一句话。"), 1);
        assertEquals(1, hits.size());
        assertEquals("公司营收同比增长四成以上。", hits.get(0));
    }

    @Test
    void mockPipelineRunsEndToEnd() {
        FidelityService svc = new FidelityService(new MockJevClient(Map.of("edited_candidate[", 0.9), 0.2));
        FidelityReport r = svc.checkFacts(
                List.of("Revenue grew 40 percent year over year in 2024."),
                List.of("Revenue grew 40 percent year over year in 2024."));
        // mock Choice 固定回 criteria 首项 preserved（conf 0.9）→ PRESERVED → FAITHFUL
        assertEquals("PRESERVED", r.factVerdicts().get(0).verdict());
        assertEquals("FAITHFUL", r.status());
    }
}
