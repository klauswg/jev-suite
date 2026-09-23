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
}
