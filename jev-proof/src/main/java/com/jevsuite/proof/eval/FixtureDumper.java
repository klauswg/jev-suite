package com.jevsuite.proof.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevsuite.proof.subs.SubtitleFetcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 真实字幕 fixture 采集：yt-dlp 抓字幕 → 45s 合并 → eval/fixtures/<videoId>.json。
 * 只跑一次，之后 eval 离线读 fixture，避免反复请求 YouTube 吃 429。
 * 用法：java ... FixtureDumper <outDir> <videoId1> [videoId2 ...]
 */
public final class FixtureDumper {

    private FixtureDumper() {}

    public static void main(String[] args) throws Exception {
        loadDotEnv();
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        String ytdlp = System.getProperty("YTDLP_PATH", "yt-dlp");
        SubtitleFetcher fetcher = new SubtitleFetcher(ytdlp);
        ObjectMapper om = new ObjectMapper();

        int ok = 0;
        for (int i = 1; i < args.length; i++) {
            String id = args[i];
            try {
                List<SubtitleFetcher.Segment> segs = SubtitleFetcher.merge(
                        fetcher.fetch("https://www.youtube.com/watch?v=" + id), 45);
                Map<String, Object> fixture = Map.of(
                        "videoId", id,
                        "url", "https://www.youtube.com/watch?v=" + id,
                        "segmentCount", segs.size(),
                        "segments", segs);
                om.writerWithDefaultPrettyPrinter()
                        .writeValue(outDir.resolve(id + ".json").toFile(), fixture);
                ok++;
                System.out.println("OK  " + id + "  mergedSegments=" + segs.size());
            } catch (Exception e) {
                System.out.println("SKIP " + id + "  " + e.getMessage());
            }
        }
        System.out.println("done: " + ok + "/" + (args.length - 1) + " fixtures");
        if (ok == 0) System.exit(1);
    }

    private static void loadDotEnv() {
        Path p = Path.of(".env");
        if (!Files.exists(p)) return;
        try {
            for (String line : Files.readAllLines(p)) {
                line = line.trim();
                int i = line.indexOf('=');
                if (i > 0 && !line.startsWith("#")) {
                    System.setProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
        } catch (IOException ignored) {}
    }
}
