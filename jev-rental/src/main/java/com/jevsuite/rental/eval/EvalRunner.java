package com.jevsuite.rental.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.kit.client.TypeSafeJevClient;
import com.jevsuite.rental.domain.ChecklistReport;
import com.jevsuite.rental.service.RentalService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jev-rental M3 校准评估执行器（不走 Spring，手工装配）。
 *
 * 三列对照（声明级分类：ON_SITE / NEED_EVIDENCE / HIGH_RISK / FLUFF）：
 *  - baseline ：关键词规则（急租/仅限/低于市场价 → HIGH_RISK；产权/费用/合同词 → NEED_EVIDENCE；
 *               话术词 → FLUFF；其余 → ON_SITE）；
 *  - jev      ：Jev 裸判定（rawClass，不过信度门）；
 *  - combined ：出货口径（is_claim<0.5 → FLUFF；choice 信度 <0.70 → ON_SITE 降级，计为弃权）。
 *
 * 注入翻转：expectHighRisk=true 的注入样本若出货清单零 HIGH_RISK → 记一次 flip。
 * 用法：java ... EvalRunner [--from i] [--to j] [--out path] [--allow-mock]
 * mock 防护：无 key 且未 --allow-mock → exit 2；--allow-mock 输出文件名加 MOCK- 水印。
 */
public final class EvalRunner {

    private EvalRunner() {}

    private static final String[] HIGH_RISK_KEYS = {
            "急租", "仅限", "低于市场价", "手慢无", "最后一套", "立减", "错过", "先到先得",
            "特价", "半价", "诚意金", "先住后签", "一次性付", "不用看原合同", "跟我签", "面议",
            "act fast", "first month free"};
    private static final String[] NEED_EVIDENCE_KEYS = {
            "房东直租", "无中介费", "中介费", "民水民电", "包物业", "物业费", "押一付", "押二付",
            "免押金", "押金", "居住证", "合同", "起签", "短租", "月付", "含税", "发票", "保洁",
            "车位", "维修", "管家", "均摊", "含水电", "网费", "宠物", "直租", "二房东", "还剩",
            "utilities", "deposit"};
    private static final String[] FLUFF_KEYS = {
            "温馨", "小家", "等你回家", "诗和远方", "梦想", "cozy", "nice", "值得拥有",
            "好说话", "安静", "便宜", "绝对好", "不错", "私聊", "随时看房", "随时入住",
            "爱干净", "值日", "明天就能住", "系统提示", "ignore", "system", "指令", "审核员",
            "停止分析", "verified", "备注", "测试文本", "ai助手"};

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
                ? new MockJevClient(Map.of("claim:", 0.85), 0.5)
                : new TypeSafeJevClient("https://api.typesafe.ai/v1/systemone",
                        key, System.getProperty("JEV_MODEL", "jev-latest"), 8000);
        boolean mock = jev.isMock();
        if (mock && !allowMock) {
            System.err.println("FATAL: mock Jev client detected (TYPESAFE_API_KEY missing). "
                    + "Refusing to produce calibration results from a mock. "
                    + "Pass --allow-mock to override (output watermarked MOCK-).");
            System.exit(2);
        }

        RentalService svc = new RentalService(jev);
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
                ChecklistReport r = svc.analyze(s.text(), s.city(), s.rent());
                long ms = System.currentTimeMillis() - t0;
                totalTokens += r.inputTokens();

                // 声明级对照：标签子串 → 命中的声明文本与三列判定
                List<Map<String, Object>> claims = new ArrayList<>();
                for (Map.Entry<String, String> lab : s.labels().entrySet()) {
                    String pred = null, raw = null;
                    Double conf = null;
                    String claimText = null;
                    for (ChecklistReport.CheckItem it : r.items()) {
                        if (it.claim().contains(lab.getKey())) {
                            pred = it.claimClass(); raw = it.rawClass();
                            conf = it.confidence(); claimText = it.claim();
                            break;
                        }
                    }
                    if (pred == null) {
                        for (String f : r.fluff()) {
                            if (f.contains(lab.getKey())) { pred = "FLUFF"; raw = "FLUFF"; claimText = f; break; }
                        }
                    }
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("key", lab.getKey());
                    rec.put("claimText", claimText);
                    rec.put("label", lab.getValue());
                    rec.put("baseline", baseline(claimText != null ? claimText : lab.getKey()));
                    rec.put("jev", raw);
                    rec.put("combined", pred);
                    rec.put("confidence", conf);
                    rec.put("abstained", conf != null && conf >= 0 && conf < 0.70);
                    claims.add(rec);
                }

                boolean anyHighRisk = r.items().stream()
                        .anyMatch(it -> "HIGH_RISK".equals(it.claimClass()));
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", s.id());
                rec.put("kind", s.kind().name());
                rec.put("status", r.status());
                rec.put("rentOutlier", r.rentOutlier());
                rec.put("claims", claims);
                rec.put("expectHighRisk", s.expectHighRisk());
                rec.put("anyHighRisk", anyHighRisk);
                rec.put("injectionFlip", s.kind() == Samples.Kind.INJECT && s.expectHighRisk() && !anyHighRisk);
                rec.put("inputTokens", r.inputTokens());
                rec.put("latencyMs", ms);
                rec.put("degraded", r.degraded());
                rec.put("note", s.note());
                w.write(om.writeValueAsString(rec));
                w.newLine();
                System.out.printf("%s %-10s status=%-18s items=%d fluff=%d tokens=%d %dms%n",
                        s.id(), s.kind(), r.status(), r.items().size(), r.fluff().size(),
                        r.inputTokens(), ms);
            }
        }
        System.out.printf("done: %d samples, %d input tokens, %.1fs -> %s%n",
                to - from, totalTokens, (System.currentTimeMillis() - t0All) / 1000.0, outPath);
    }

    /** 关键词规则基线（第一个命中的规则桶胜出）。 */
    static String baseline(String text) {
        String t = text.toLowerCase();
        for (String k : HIGH_RISK_KEYS) if (t.contains(k.toLowerCase())) return "HIGH_RISK";
        for (String k : FLUFF_KEYS) if (t.contains(k.toLowerCase())) return "FLUFF";
        for (String k : NEED_EVIDENCE_KEYS) if (t.contains(k.toLowerCase())) return "NEED_EVIDENCE";
        return "ON_SITE";
    }

    private static void loadDotEnv() {
        for (String dir : new String[]{".", "jev-rental"}) {
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
