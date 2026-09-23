package com.jevsuite.fit.service;

import com.jevsuite.fit.domain.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdParserTest {

    @Test
    void splitsBulletsAndDetectsNiceToHave() {
        String jd = """
                Senior Backend Engineer
                Responsibilities:
                - Build and maintain low-latency trading services
                Requirements:
                • 5+ years of backend development experience in Java or Go
                • Deep understanding of distributed systems and message queues
                • Experience with Kubernetes is preferred
                • 必须熟悉资金清结算业务流程
                """;
        List<Requirement> reqs = JdParser.parse(jd);
        assertEquals(4, reqs.size());
        assertTrue(reqs.get(0).mustHave());    // 5+ years
        assertTrue(reqs.get(1).mustHave());    // Deep understanding
        assertFalse(reqs.get(2).mustHave());   // preferred → nice-to-have
        assertTrue(reqs.get(3).mustHave());    // 必须熟悉 → must-have
    }

    @Test
    void skipsShortTitlesAndCapsAt20() {
        StringBuilder jd = new StringBuilder("Title\n");
        for (int i = 0; i < 30; i++) jd.append("- requirement number ").append(i)
                .append(" requires solid experience\n");
        assertEquals(20, JdParser.parse(jd.toString()).size());
    }

    @Test
    void emptyInputGivesEmptyList() {
        assertTrue(JdParser.parse(null).isEmpty());
        assertTrue(JdParser.parse("short\nlines\nonly").isEmpty());
    }

    /** v0.1.1 回归：真实充提岗 JD——任职要求小节后 7 条全收（含无信号词的「具备 Owner 意识」）。 */
    @Test
    void requirementSectionCapturesSignalLessItems() {
        String jd = """
                岗位职责：
                1. 负责充提业务后端系统的设计、开发与持续优化。
                2. 与 Wallet、风控、合规等团队协作完成业务编排。
                任职要求：
                1. 5 年以上后端研发经验，具备扎实的后端开发及系统设计能力。
                2. 有大所 CEX 充提业务研发经验优先。
                3. 有钱包、资产、支付、清结算等资金相关系统研发经验优先。
                4. 熟悉 Go / PHP 等至少一种后端技术栈。
                5. 有大型系统重构、架构升级经验优先。
                6. 具备 Owner 意识与跨团队项目推动能力。
                7. 有 Travel Rule、AI Coding 实践经验优先。
                """;
        List<Requirement> reqs = JdParser.parse(jd);
        assertEquals(7, reqs.size());
        assertTrue(reqs.stream().anyMatch(r -> r.text().contains("Owner")));
        assertTrue(reqs.get(0).mustHave());
        assertFalse(reqs.get(1).mustHave());   // 优先 → nice-to-have
    }
}
