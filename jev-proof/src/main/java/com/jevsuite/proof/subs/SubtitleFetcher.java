package com.jevsuite.proof.subs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * YouTube 字幕抓取（M2 实测修正：直抓 timedtext 已被 poToken 封锁，改走 yt-dlp 外部进程）。
 * 依赖：yt-dlp 可执行文件（jev.ytdlp.path，默认 PATH 里的 yt-dlp）。
 * 抓不到/无字幕 → SubtitleException（上层映射 UNVERIFIABLE，硬规则不调 Jev）。
 */
@Component
public class SubtitleFetcher {

    private static final Logger log = LoggerFactory.getLogger(SubtitleFetcher.class);
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/watch\\?.*v=|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private static final Pattern CUE = Pattern.compile(
            "(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})");

    private final String ytDlpPath;

    public SubtitleFetcher(@Value("${jev.ytdlp.path:yt-dlp}") String ytDlpPath) {
        this.ytDlpPath = ytDlpPath;
    }

    public record Segment(double startSec, double endSec, String text) {}

    public List<Segment> fetch(String videoUrl) {
        String videoId = extractVideoId(videoUrl);
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("jevproof-subs");
            Process p = new ProcessBuilder(ytDlpPath,
                    "--skip-download", "--write-subs", "--write-auto-subs",
                    "--sub-langs", "en,en-US,en-GB,en-orig",
                    "--sub-format", "vtt", "--no-warnings", "--quiet",
                    "-o", videoId, "-P", tmp.toString(), videoUrl)
                    .redirectErrorStream(true)
                    .start();
            String out;
            if (!p.waitFor(90, TimeUnit.SECONDS)) {   // 先等退出再读输出，否则 readAllBytes 会无限阻塞
                p.destroyForcibly();
                throw new SubtitleException("yt-dlp timeout");
            }
            out = new String(p.getInputStream().readAllBytes());
            List<Path> vtts;
            try (Stream<Path> s = Files.list(tmp)) {
                vtts = s.filter(f -> f.toString().endsWith(".vtt"))
                        .sorted(Comparator.comparing(Path::toString)).toList();
            }
            if (vtts.isEmpty()) throw new SubtitleException("no subtitle file: " + out.trim());
            // 优先手动字幕（.en.vtt），其次自动（.en-orig 等）
            Path pick = vtts.stream().filter(f -> f.getFileName().toString().matches(".*\\.en\\.vtt"))
                    .findFirst().orElse(vtts.get(0));
            List<Segment> segs = parseVtt(Files.readString(pick));
            if (segs.isEmpty()) throw new SubtitleException("vtt parsed to 0 segments");
            return segs;
        } catch (SubtitleException e) {
            throw e;
        } catch (IOException e) {
            throw new SubtitleException("yt-dlp exec failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SubtitleException("interrupted");
        } finally {
            if (tmp != null) {
                try (Stream<Path> s = Files.walk(tmp)) {
                    s.sorted(Comparator.reverseOrder()).forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }
    }

    private String extractVideoId(String url) {
        Matcher m = VIDEO_ID.matcher(url == null ? "" : url);
        if (!m.find()) throw new SubtitleException("cannot parse video id from: " + url);
        return m.group(1);
    }

    /** VTT → 段；剥标签/实体，去掉自动字幕连续的重复行。 */
    static List<Segment> parseVtt(String vtt) {
        List<Segment> out = new ArrayList<>();
        String[] lines = vtt.split("\\R");
        double start = -1, end = -1;
        StringBuilder text = new StringBuilder();
        String lastEmitted = null;
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "";
            Matcher m = CUE.matcher(line);
            if (m.find()) {
                flush(out, start, end, text);
                start = sec(m, 1); end = sec(m, 5);
                text.setLength(0);
            } else if (line.isBlank() && start >= 0) {
                String emitted = flush(out, start, end, text);
                start = -1;
                text.setLength(0);
                // 自动字幕同一句话常在相邻 cue 重复——连续相同只留一条
                if (emitted != null && emitted.equals(lastEmitted)) out.remove(out.size() - 1);
                else if (emitted != null) lastEmitted = emitted;
            } else if (start >= 0 && !line.startsWith("WEBVTT") && !line.contains("Kind:") && !line.contains("Language:")) {
                String clean = line.replaceAll("<[^>]+>", " ")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&#39;", "'")
                        .replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(clean);
                }
            }
        }
        return out;
    }

    private static String flush(List<Segment> out, double start, double end, StringBuilder text) {
        String t = text.toString().trim();
        if (start >= 0 && !t.isEmpty()) {
            out.add(new Segment(start, end, t));
            return t;
        }
        return null;
    }

    private static double sec(Matcher m, int base) {
        return Integer.parseInt(m.group(base)) * 3600
                + Integer.parseInt(m.group(base + 1)) * 60
                + Integer.parseInt(m.group(base + 2))
                + Integer.parseInt(m.group(base + 3)) / 1000.0;
    }

    /** 细段合并为 ~45s 窗口（控制 state 长度）。 */
    public static List<Segment> merge(List<Segment> fine, double windowSec) {
        List<Segment> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        double wStart = -1, wEnd = -1;
        for (Segment s : fine) {
            if (wStart < 0) wStart = s.startSec();
            if (s.startSec() - wStart > windowSec && sb.length() > 0) {
                out.add(new Segment(wStart, wEnd, sb.toString().trim()));
                sb.setLength(0);
                wStart = s.startSec();
            }
            sb.append(s.text()).append(' ');
            wEnd = s.endSec();
        }
        if (sb.length() > 0) out.add(new Segment(wStart, wEnd, sb.toString().trim()));
        return out;
    }

    public static class SubtitleException extends RuntimeException {
        public SubtitleException(String msg) { super(msg); }
    }
}
