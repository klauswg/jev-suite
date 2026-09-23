package com.jevsuite.fit.service;

import com.jevsuite.fit.domain.EvidenceItem;
import com.jevsuite.fit.domain.FitReport;
import com.jevsuite.fit.domain.Requirement;
import com.jevsuite.kit.client.MockJevClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FitServiceTest {

    @Test
    void resumeParserKeepsLineAnchors() {
        String resume = "张三\n\n• 5 years of Java backend development\n• Led payment clearing system\n";
        List<EvidenceItem> items = ResumeParser.parse(resume);
        assertEquals(2, items.size());          // "张三" 太短被过滤
        assertEquals(3, items.get(0).lineNo()); // 锚点 = 原始行号
    }

    @Test
    void mockPipelineGatesVerdicts() {
        // mock：state 含 "evidence[" → 0.85（SATISFIED），否则 0.15（GAP）。
        // 注意：证据选取带补齐逻辑——即使零重叠也会喂 topN 条，所以 mock 下两条都 SATISFIED；
        // 语义区分是真实 Jev 的事，mock 只验证管线与门控映射。
        FitService svc = new FitService(new MockJevClient(Map.of("evidence[", 0.85), 0.15));
        List<EvidenceItem> items = List.of(
                new EvidenceItem("e1", 3, "5 years of Java backend development with Spring Cloud"),
                new EvidenceItem("e2", 4, "Built distributed message queue consumers with Kafka"));
        List<Requirement> reqs = List.of(
                new Requirement("r1", "5+ years of backend development experience in Java", true),
                new Requirement("r2", "Familiar with COBOL mainframe systems", true));
        FitReport r = svc.analyzeItems(items, reqs);
        assertEquals("SATISFIED", r.verdicts().get(0).verdict());
        assertEquals("SATISFIED", r.verdicts().get(1).verdict());
        assertEquals("STRONG_MATCH", r.status());

        // 空简历：无证据可喂 → mock 回退 0.15 → GAP → must-have 阻断
        FitReport r2 = svc.analyzeItems(List.of(), reqs);
        assertEquals("GAP", r2.verdicts().get(0).verdict());
        assertEquals("BLOCKED_BY_MUST_HAVE", r2.status());
    }

    @Test
    void roughMatchRanksByOverlap() {
        List<EvidenceItem> items = List.of(
                new EvidenceItem("e1", 1, "I like cooking pasta every weekend"),
                new EvidenceItem("e2", 2, "8 years Java Spring Cloud microservices experience"),
                new EvidenceItem("e3", 3, "Java development"));
        List<EvidenceItem> hits = FitService.roughMatch(
                "5+ years Java development experience", items, 2);
        assertEquals(2, hits.size());
        assertEquals("e2", hits.get(0).id());   // 重叠度最高
    }
}
