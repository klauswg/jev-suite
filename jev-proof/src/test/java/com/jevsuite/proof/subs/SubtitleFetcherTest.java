package com.jevsuite.proof.subs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleFetcherTest {

    @Test
    void parseVttExtractsCuesAndDedups() {
        String vtt = """
                WEBVTT
                Kind: captions
                Language: en

                00:00:27.103 --> 00:00:29.678
                Good morning. How are you?

                00:00:29.702 --> 00:00:31.105
                Good morning. How are you?

                00:00:31.129 --> 00:00:32.797
                this video is <b>sponsored</b> by Acme &amp; Co

                """;
        List<SubtitleFetcher.Segment> segs = SubtitleFetcher.parseVtt(vtt);
        assertEquals(2, segs.size(), "连续重复行应被去重");
        assertEquals(27.103, segs.get(0).startSec(), 0.001);
        assertEquals(29.678, segs.get(0).endSec(), 0.001);
        assertEquals("Good morning. How are you?", segs.get(0).text());
        assertTrue(segs.get(1).text().contains("sponsored by Acme & Co"));
        assertFalse(segs.get(1).text().contains("<b>"));
    }

    @Test
    void mergeGroupsIntoWindows() {
        List<SubtitleFetcher.Segment> fine = List.of(
                new SubtitleFetcher.Segment(0, 2, "a"),
                new SubtitleFetcher.Segment(2, 4, "b"),
                new SubtitleFetcher.Segment(50, 52, "c"),
                new SubtitleFetcher.Segment(52, 54, "d"));
        List<SubtitleFetcher.Segment> merged = SubtitleFetcher.merge(fine, 45);
        assertEquals(2, merged.size());
        assertEquals("a b", merged.get(0).text());
        assertEquals("c d", merged.get(1).text());
    }

    @Test
    void parseEmptyYieldsEmpty() {
        assertTrue(SubtitleFetcher.parseVtt("WEBVTT\n\n").isEmpty());
    }
}
