package com.jevsuite.fidelity.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextSplitterTest {

    @Test
    void splitsEnglishSentences() {
        List<String> s = TextSplitter.split(
                "Revenue grew 40% in 2024. The company hired 200 engineers! Did margins improve?");
        assertEquals(3, s.size());
        assertEquals("Revenue grew 40% in 2024.", s.get(0));
    }

    @Test
    void splitsChineseSentences() {
        List<String> s = TextSplitter.split(
                "公司营收增长了40%。新增员工200人；利润率基本持平。");
        assertEquals(3, s.size());
    }

    @Test
    void keepsDecimalPoints() {
        List<String> s = TextSplitter.split("The rate stayed at 4.5 percent this year.");
        assertEquals(1, s.size());
    }

    @Test
    void dropsTinyFragments() {
        List<String> s = TextSplitter.split("Hi. OK. The quarterly report was published on Tuesday.");
        assertEquals(1, s.size());
    }
}
