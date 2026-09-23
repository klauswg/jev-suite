package com.jevsuite.fidelity.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevsuite.fidelity.domain.FidelityReport;
import com.jevsuite.fidelity.service.FidelityService;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jev-fidelity M3 校准评估执行器（不走 Spring，手工装配）。
 *
 * 三列对照（事实级：defect = DRIFT/LOST vs ok = PRESERVED/EQUIVALENT）：
 *  - baseline ：关键词重叠率（共有 key / 事实 key 总数）< 0.5 → defect；
 *  - jev      ：Jev 原始 choice 直出（不看置信度；无候选走 SUSPECTED_LOST 计 defect）；
 *  - combined ：产品口径 = 置信度 < 0.70 弃权（REVIEW）；SUSPECTED_LOST 计 defect。
 *
 * 用法：java ... EvalRunner [--from i] [--to j] [--out path] [--allow-mock]
 * mock 防护：无 key 且未 --allow-mock → exit 2；--allow-mock 输出文件名加 MOCK- 水印。
 */
public final class EvalRunner {

    private EvalRunner() {}

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
                ? new MockJevClient(Map.of("edited_candidate[", 0.9), 0.2)
                : new TypeSafeJevClient("https://api.typesafe.ai/v1/systemone",
                        key, System.getProperty("JEV_MODEL", "jev-latest"), 8000);
        boolean mock = jev.isMock();
        if (mock && !allowMock) {
            System.err.println("FATAL: mock Jev client detected (TYPESAFE_API_KEY missing). "
                    + "Refusing to produce calibration results from a mock. "
                    + "Pass --allow-mock to override (output watermarked MOCK-).");
            System.exit(2);
        }

        FidelityService svc = new FidelityService(jev);
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
                FidelityReport r = svc.checkFacts(s.orig(), s.edit());
                long ms = System.currentTimeMillis() - t0;
                totalTokens += r.inputTokens();

                List<Map<String, Object>> facts = new ArrayList<>();
                for (FidelityReport.FactVerdict v : r.factVerdicts()) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("factId", v.factId());
                    rec.put("label", s.factLabels().get(v.factId()));
                    rec.put("verdict", v.verdict());
                    rec.put("jevChoice", v.jevChoice());
                    rec.put("choiceConf", v.choiceConfidence());
                    rec.put("aligned", v.alignedEdited().size());
                    rec.put("baselineOverlap", overlapRatio(v.originalSentence(), s.edit()));
                    facts.add(rec);
                }

                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", s.id());
                rec.put("kind", s.kind().name());
                rec.put("facts", facts);
                rec.put("status", r.status());
                rec.put("fidelityBand", r.fidelityBand());
                rec.put("newContents", r.newContents().size());
                rec.put("inputTokens", r.inputTokens());
                rec.put("latencyMs", ms);
                rec.put("degraded", r.degraded());
                rec.put("note", s.note());
                w.write(om.writeValueAsString(rec));
                w.newLine();
                System.out.printf("%s %-6s status=%-18s band=%s tokens=%d %dms%n",
                        s.id(), s.kind(), r.status(), r.fidelityBand(), r.inputTokens(), ms);
            }
        }
        System.out.printf("done: %d samples, %d input tokens, %.1fs -> %s%n",
                to - from, totalTokens, (System.currentTimeMillis() - t0All) / 1000.0, outPath);
    }

    /** 基线相似度：事实句关键词在全部编辑句中的最大重叠率。 */
    static double overlapRatio(String fact, List<String> edited) {
        List<String> keys = FidelityService.keysOf(fact);
        if (keys.isEmpty()) return 0;
        double best = 0;
        for (String e : edited) {
            String el = e.toLowerCase();
            long hit = keys.stream().filter(el::contains).count();
            best = Math.max(best, hit / (double) keys.size());
        }
        return best;
    }

    private static void loadDotEnv() {
        for (String dir : new String[]{".", "jev-fidelity"}) {
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
