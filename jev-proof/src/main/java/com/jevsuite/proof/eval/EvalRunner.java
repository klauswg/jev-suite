package com.jevsuite.proof.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;
import com.jevsuite.proof.domain.AcceptanceResult;
import com.jevsuite.proof.service.ProofService;
import com.jevsuite.proof.subs.SubtitleFetcher;
import com.jevsuite.proof.subs.SubtitleFetcher.Segment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 校准评估执行器（不走 Spring，手工装配）。
 *
 * 三列对照（要点级二分类：fulfilled / not）：
 *  - baseline ：要点关键词（>3 字符）是否出现在完整转写中；
 *  - jev      ：管线喂给 Jev 的证据 → noul ≥ 0.5（裸判定，不过门控）；
 *  - combined ：当前产品口径 = 门控后 PASS（REVIEW 计为 abstain，单独统计）。
 *
 * 用法：
 *   java ... EvalRunner [--from i] [--to j] [--out path] [--fixtures dir] [--allow-mock]
 *
 * mock 防护：检测到 MockJevClient 且未 --allow-mock → exit 2（宁可失败也不出假数据）；
 * --allow-mock 时输出文件名自动加 MOCK- 前缀水印。
 */
public final class EvalRunner {

    private EvalRunner() {}

    public static void main(String[] args) throws Exception {
        int from = 0, to = Integer.MAX_VALUE;
        boolean allowMock = false;
        String out = null, fixturesDir = "eval/fixtures";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = Integer.parseInt(args[++i]);
                case "--to" -> to = Integer.parseInt(args[++i]);
                case "--out" -> out = args[++i];
                case "--fixtures" -> fixturesDir = args[++i];
                case "--allow-mock" -> allowMock = true;
                default -> { System.err.println("unknown arg: " + args[i]); System.exit(64); }
            }
        }

        loadDotEnv();
        String key = System.getProperty("TYPESAFE_API_KEY", "");
        JevClient jev = key.isBlank()
                ? new MockJevClient(Map.of("evidence[", 0.9), 0.9)
                : new TypeSafeJevClient("https://api.typesafe.ai/v1/systemone",
                        key, System.getProperty("JEV_MODEL", "jev-latest"), 8000);
        boolean mock = jev.isMock();
        if (mock && !allowMock) {
            System.err.println("FATAL: mock Jev client detected (TYPESAFE_API_KEY missing). "
                    + "Refusing to produce calibration results from a mock. "
                    + "Pass --allow-mock to override (output watermarked MOCK-).");
            System.exit(2);
        }

        ProofService svc = new ProofService(
                new SubtitleFetcher(System.getProperty("YTDLP_PATH", "yt-dlp")), jev);
        List<Samples.EvalSample> samples = Samples.all();
        to = Math.min(to, samples.size());
        if (from < 0 || from >= to) {
            System.err.println("empty range: from=" + from + " to=" + to + " (samples=" + samples.size() + ")");
            System.exit(64);
        }

        Path outPath = Path.of(out != null ? out
                : "eval/results/" + (mock ? "MOCK-" : "") + "run-" + from + "-" + to + ".jsonl");
        Files.createDirectories(outPath.getParent().toAbsolutePath());
        ObjectMapper om = new ObjectMapper();
        long totalTokens = 0, t0All = System.currentTimeMillis();

        try (BufferedWriter w = Files.newBufferedWriter(outPath)) {
            for (int i = from; i < to; i++) {
                Samples.EvalSample s = samples.get(i);
                List<Segment> segs = s.kind() == Samples.Kind.REAL
                        ? loadFixture(fixturesDir, s.videoId()) : s.segments();
                String transcript = join(segs);

                long t0 = System.currentTimeMillis();
                AcceptanceResult r = svc.evaluateSegments(s.brief(), "eval://" + s.id(), segs);
                long ms = System.currentTimeMillis() - t0;
                totalTokens += r.inputTokens();

                List<Map<String, Object>> points = new ArrayList<>();
                for (AcceptanceResult.PointVerdict v : r.pointVerdicts()) {
                    String pointText = s.brief().points().stream()
                            .filter(p -> p.id().equals(v.pointId())).findFirst().orElseThrow().text();
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("pointId", v.pointId());
                    rec.put("label", s.pointLabels().get(v.pointId()));
                    rec.put("baseline", keywordBaseline(pointText, transcript));
                    rec.put("noul", v.noul());
                    rec.put("jev", v.noul() >= 0.5);
                    rec.put("gate", v.verdict());           // PASS / FAIL / REVIEW
                    rec.put("evidenceCount", v.evidence().size());
                    points.add(rec);
                }

                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", s.id());
                rec.put("kind", s.kind().name());
                rec.put("videoId", s.videoId());
                rec.put("points", points);
                rec.put("disclosureLabel", s.disclosureLabel());
                rec.put("disclosurePred", r.disclosure());
                rec.put("status", r.status());
                rec.put("inputTokens", r.inputTokens());
                rec.put("latencyMs", ms);
                rec.put("degraded", r.degraded());
                rec.put("note", s.note());
                w.write(om.writeValueAsString(rec));
                w.newLine();
                System.out.printf("%s %-6s status=%-12s disc=%-9s tokens=%d %dms%n",
                        s.id(), s.kind(), r.status(), r.disclosure(), r.inputTokens(), ms);
            }
        }
        System.out.printf("done: %d samples, %d input tokens, %.1fs -> %s%n",
                to - from, totalTokens, (System.currentTimeMillis() - t0All) / 1000.0, outPath);
    }

    /** 关键词基线：要点词（>3 字符）任一出现在完整转写 → fulfilled。 */
    static boolean keywordBaseline(String pointText, String transcript) {
        String t = transcript.toLowerCase();
        return Arrays.stream(pointText.toLowerCase().split("[^a-z0-9]+"))
                .filter(wd -> wd.length() > 3)
                .anyMatch(t::contains);
    }

    private static String join(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segs) sb.append(s.text()).append(' ');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Segment> loadFixture(String dir, String videoId) throws IOException {
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> f = om.readValue(Path.of(dir, videoId + ".json").toFile(), Map.class);
        List<Segment> out = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) f.get("segments")) {
            out.add(new Segment(((Number) s.get("startSec")).doubleValue(),
                    ((Number) s.get("endSec")).doubleValue(), (String) s.get("text")));
        }
        return out;
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
