package com.jevsuite.fit.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevsuite.fit.domain.FitReport;
import com.jevsuite.fit.service.FitService;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;

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
 * jev-fit M3 校准评估执行器（不走 Spring，手工装配）。
 *
 * 三列对照（要求级二分类：satisfied / not）：
 *  - baseline ：要求关键词（>3 字符、去虚词）是否出现在完整简历中（Jobscan 同款思路）；
 *  - jev      ：noul ≥ 0.5（裸判定，不过门控）；
 *  - combined ：产品口径 = 门控后 SATISFIED/GAP（UNCERTAIN 计为 abstain，单独统计）。
 *
 * 用法：java ... EvalRunner [--from i] [--to j] [--out path] [--allow-mock]
 * mock 防护：无 key 且未 --allow-mock → exit 2；--allow-mock 输出文件名加 MOCK- 水印。
 */
public final class EvalRunner {

    private EvalRunner() {}

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "with", "from", "that", "this", "these", "those", "will", "would", "should",
            "shall", "must", "have", "been", "more", "than", "into", "over", "such",
            "when", "what", "which", "where", "also", "very", "much", "many", "about",
            "after", "before", "between", "both", "each", "other", "some", "only",
            "same", "then", "there", "here", "under", "using", "used");

    public static void main(String[] args) throws Exception {
        int from = 0, to = Integer.MAX_VALUE;
        boolean allowMock = false;
        String out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = Integer.parseInt(args[++i]);
                case "--to" -> to = Integer.parseInt(args[++i]);
                case "--out" -> out = args[++i];
                case "--allow-mock" -> allowMock = true;
                default -> { System.err.println("unknown arg: " + args[i]); System.exit(64); }
            }
        }

        loadDotEnv();
        String key = System.getProperty("TYPESAFE_API_KEY", "");
        JevClient jev = key.isBlank()
                ? new MockJevClient(Map.of("evidence[", 0.85), 0.15)
                : new TypeSafeJevClient("https://api.typesafe.ai/v1/systemone",
                        key, System.getProperty("JEV_MODEL", "jev-latest"), 8000);
        boolean mock = jev.isMock();
        if (mock && !allowMock) {
            System.err.println("FATAL: mock Jev client detected (TYPESAFE_API_KEY missing). "
                    + "Refusing to produce calibration results from a mock. "
                    + "Pass --allow-mock to override (output watermarked MOCK-).");
            System.exit(2);
        }

        FitService svc = new FitService(jev);
        List<Samples.EvalSample> samples = Samples.all();
        to = Math.min(to, samples.size());
        if (from < 0 || from >= to) {
            System.err.println("empty range: from=" + from + " to=" + to);
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
                long t0 = System.currentTimeMillis();
                FitReport r = svc.analyzeItems(s.evidence(), s.requirements());
                long ms = System.currentTimeMillis() - t0;
                totalTokens += r.inputTokens();

                List<Map<String, Object>> reqs = new ArrayList<>();
                for (FitReport.RequirementVerdict v : r.verdicts()) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("reqId", v.requirementId());
                    rec.put("label", s.reqLabels().get(v.requirementId()));
                    rec.put("baseline", keywordBaseline(v.requirementText(), s.resumeText()));
                    rec.put("noul", v.noul());
                    rec.put("jev", v.noul() >= 0.5);
                    rec.put("gate", v.verdict());       // SATISFIED / GAP / UNCERTAIN
                    rec.put("gapType", v.gapType());
                    rec.put("rewriteEligible", v.rewriteEligible());
                    rec.put("evidenceCount", v.evidence().size());
                    reqs.add(rec);
                }

                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", s.id());
                rec.put("kind", s.kind().name());
                rec.put("reqs", reqs);
                rec.put("status", r.status());
                rec.put("fitBand", r.fitBand());
                rec.put("inputTokens", r.inputTokens());
                rec.put("latencyMs", ms);
                rec.put("degraded", r.degraded());
                rec.put("note", s.note());
                w.write(om.writeValueAsString(rec));
                w.newLine();
                System.out.printf("%s %-6s status=%-20s band=%s tokens=%d %dms%n",
                        s.id(), s.kind(), r.status(), r.fitBand(), r.inputTokens(), ms);
            }
        }
        System.out.printf("done: %d samples, %d input tokens, %.1fs -> %s%n",
                to - from, totalTokens, (System.currentTimeMillis() - t0All) / 1000.0, outPath);
    }

    /** 关键词基线（Jobscan 思路）：要求词（>3 字符、去虚词）任一出现在简历 → satisfied。 */
    static boolean keywordBaseline(String reqText, String resumeText) {
        String t = resumeText.toLowerCase();
        return Arrays.stream(reqText.toLowerCase().split("[^a-z0-9\\p{IsHan}]+"))
                .filter(wd -> wd.length() > 3 && !STOPWORDS.contains(wd))
                .anyMatch(t::contains);
    }

    private static void loadDotEnv() {
        for (String dir : new String[]{".", "jev-fit"}) {
            Path p = Path.of(dir, ".env");
            if (!Files.exists(p)) continue;
            try {
                for (String line : Files.readAllLines(p)) {
                    line = line.trim();
                    int i = line.indexOf('=');
                    if (i > 0 && !line.startsWith("#")) {
                        System.setProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
                }
                return;
            } catch (IOException ignored) {}
        }
    }
}
