package com.jevsuite.rental.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListingParserTest {

    @Test
    void splitsChineseSentences() {
        List<ListingParser.Claim> claims = ListingParser.parse(
                "朝南主卧，采光好。家电齐全，拎包入住！押金一个月。");
        assertEquals(3, claims.size());
        assertEquals("朝南主卧，采光好", claims.get(0).text());
        assertEquals("押金一个月", claims.get(2).text());
    }

    @Test
    void splitsEnglishAndNewlines() {
        List<ListingParser.Claim> claims = ListingParser.parse(
                "Close to subway. Fully furnished.\nNo agency fee!");
        assertEquals(3, claims.size());
        assertEquals("No agency fee", claims.get(2).text());
    }

    @Test
    void filtersBlankAndSymbolOnly() {
        List<ListingParser.Claim> claims = ListingParser.parse("，，。！！\n  \n电梯房。");
        assertEquals(1, claims.size());
    }

    @Test
    void nullAndEmpty() {
        assertTrue(ListingParser.parse(null).isEmpty());
        assertTrue(ListingParser.parse("。").isEmpty());
    }
}
