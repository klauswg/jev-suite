package com.jevsuite.fidelity.service;

import com.jevsuite.fidelity.domain.FidelityReport;
import com.jevsuite.kit.client.Answer;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.JevResponse;
import com.jevsuite.kit.client.TypeSafeJevClient.JevUnavailableException;
import com.jevsuite.kit.state.ExternalStringSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 保真检查主链路（jev-fidelity PRD §3）：
 * 原稿分句 → 逐句在编辑稿中粗筛候选（英文关键词 + 中文 bigram）
 * → 一次 Jev 调用两问（is_fact Noul + fidelity Choice）
 * → 无候选不判"丢失"，判 SUSPECTED_LOST 降信度（防对齐错误传导）。
 * 门控：DRIFT/LOST（置信 ≥0.7）≥ 1 → NOT_FAITHFUL；仅低信度 → NEEDS_HUMAN_REVIEW。
 * 定位写死：不管世界真相，只管编辑有没有把稿子改丢东西。
 */
@Service
public class FidelityService {

    private static final Logger log = LoggerFactory.getLogger(FidelityService.class);

    private static final double CHOICE_CONF_HIGH = 0.70;
    private static final int MAX_NEW_CONTENT_CALLS = 5;   // 新增内容判定成本上限

    private final JevClient jev;
    private final ExternalStringSanitizer san = new ExternalStringSanitizer();

    public FidelityService(JevClient jev) {
        this.jev = jev;
    }

    /** API 入口：原始双文本。 */
    public FidelityReport check(String originalText, String editedText) {
        List<String> orig = TextSplitter.split(originalText);
        List<String> edit = TextSplitter.split(editedText);
        if (orig.isEmpty() || edit.isEmpty()) {
            return new FidelityReport(UUID.randomUUID().toString().substring(0, 8),
                    "UNPARSEABLE", null, List.of(), List.of(), 0, false);
        }
        return checkFacts(orig, edit);
    }

    /** 核心判定（public：eval 直接注入分句结果）。 */
    public FidelityReport checkFacts(List<String> orig, List<String> edit) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long tokens = 0;
        boolean degraded = false;
        List<FidelityReport.FactVerdict> verdicts = new ArrayList<>();
        Set<String> alignedUsed = new HashSet<>();

        int n = 0;
        for (String fact : orig) {
            String factId = "f" + (++n);
            List<String> candidates = roughMatch(fact, edit, 3);
            if (candidates.isEmpty()) {
                // 对齐无候选：不判"丢失"，降信度处理（PRD §3）
                verdicts.add(new FidelityReport.FactVerdict(factId, san.text(fact, 300),
                        "SUSPECTED_LOST", null, 0.0, List.of()));
                continue;
            }
            alignedUsed.addAll(candidates);
            Map<String, Object> q = Map.of(
                    "is_fact", Map.of("type", "noul",
                            "instructions", "The original sentence is a checkable factual statement "
                                    + "(not opinion, rhetoric, or greeting)."),
                    "fidelity", Map.of("type", "choice",
                            "instructions", "How faithfully do the edited candidates preserve the original sentence?",
                            "criteria", Map.of(
                                    "preserved", "kept verbatim or trivially reformatted",
                                    "equivalent", "rephrased with identical meaning",
                                    "drift", "meaning changed: numbers, negation, entities, qualifiers altered",
                                    "lost", "none of the candidates carry this information")));
            try {
                JevResponse r = jev.evaluate(renderState(fact, candidates), q);
                tokens += r.inputTokens();
                Answer.NoulAnswer isFact = (Answer.NoulAnswer) r.answers().get("is_fact");
                if (isFact.value() < 0.5) {   // 意见/修辞不是事实单元，排除出汇总
                    verdicts.add(new FidelityReport.FactVerdict(factId, san.text(fact, 300),
                            "NOT_FACT", null, 1.0 - isFact.value(), List.of()));
                    continue;
                }
                Answer.ChoiceAnswer c = (Answer.ChoiceAnswer) r.answers().get("fidelity");
                String verdict = c.confidence() >= CHOICE_CONF_HIGH
                        ? c.choice().toUpperCase() : "REVIEW";
                verdicts.add(new FidelityReport.FactVerdict(factId, san.text(fact, 300),
                        verdict, c.choice(), c.confidence(),
                        candidates.stream().map(s -> san.text(s, 200)).toList()));
            } catch (JevUnavailableException e) {
                degraded = true;
                verdicts.add(new FidelityReport.FactVerdict(factId, san.text(fact, 300),
                        "REVIEW", null, -1, List.of()));
            }
        }

        // 编辑稿新增内容：从未被任何事实对齐到的句子 → Noul 是否需要独立信源（上限 5 条）
        List<FidelityReport.NewContent> newContents = new ArrayList<>();
        int judged = 0;
        for (String s : edit) {
            if (alignedUsed.contains(s)) continue;
            if (judged >= MAX_NEW_CONTENT_CALLS) {
                newContents.add(new FidelityReport.NewContent(san.text(s, 200), null, -1));
                continue;
            }
            judged++;
            try {
                JevResponse r = jev.evaluate(
                        "Edited-article sentence not present in the original.\nsentence: "
                                + san.text(s, 300) + '\n',
                        Map.of("needs_source", Map.of("type", "noul",
                                "instructions", "This newly added sentence makes factual claims that "
                                        + "require an independent source to verify.")));
                tokens += r.inputTokens();
                Answer.NoulAnswer na = (Answer.NoulAnswer) r.answers().get("needs_source");
                newContents.add(new FidelityReport.NewContent(san.text(s, 200),
                        na.value() >= 0.5, na.value()));
            } catch (JevUnavailableException e) {
                degraded = true;
                newContents.add(new FidelityReport.NewContent(san.text(s, 200), null, -1));
            }
        }

        // 整体保真度 5 档
        Integer band = null;
        try {
            JevResponse meta = jev.evaluate(renderMetaState(verdicts), Map.of(
                    "fidelity_band", Map.of("type", "score",
                            "instructions", "Overall edit fidelity.",
                            "criteria", List.of("broken: facts lost or altered throughout",
                                    "poor: multiple drifts", "fair: minor drift or losses",
                                    "good: equivalent rephrasing only", "excellent: fully preserved"))));
            tokens += meta.inputTokens();
            band = ((Answer.ScoreAnswer) meta.answers().get("fidelity_band")).ordinal();
        } catch (JevUnavailableException e) {
            degraded = true;
        }

        return new FidelityReport(runId, overall(verdicts, degraded), band,
                verdicts, newContents, tokens, degraded);
    }

    private String overall(List<FidelityReport.FactVerdict> vs, boolean degraded) {
        boolean bad = vs.stream().anyMatch(v ->
                "DRIFT".equals(v.verdict()) || "LOST".equals(v.verdict()));
        if (bad) return "NOT_FAITHFUL";
        if (degraded || vs.stream().anyMatch(v ->
                "REVIEW".equals(v.verdict()) || "SUSPECTED_LOST".equals(v.verdict()))) {
            return "NEEDS_HUMAN_REVIEW";
        }
        return "FAITHFUL";
    }

    private static final Set<String> STOPWORDS = Set.of(
            "with", "from", "that", "this", "these", "those", "will", "would", "should",
            "shall", "have", "been", "more", "than", "into", "over", "such", "when",
            "what", "which", "where", "also", "very", "much", "many", "about", "after",
            "before", "between", "both", "each", "other", "some", "only", "same",
            "then", "there", "here", "under", "were", "they", "them", "their");

    /** 关键词提取：英文取 >3 字符非虚词；中文连续段取 bigram（中文没有空格分词，且常带数字混排）。 */
    public static List<String> keysOf(String text) {
        Set<String> keys = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        for (String tok : lower.split("[^a-z0-9]+")) {
            if (tok.length() > 3 && !STOPWORDS.contains(tok)) keys.add(tok);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\p{IsHan}+").matcher(lower);
        while (m.find()) {
            String run = m.group();
            for (int i = 0; i + 2 <= run.length(); i++) keys.add(run.substring(i, i + 2));
        }
        return List.copyOf(keys);
    }

    /** 粗筛候选（纯代码，控成本）：重叠度 topN，零命中返回空（触发 SUSPECTED_LOST）。 */
    static List<String> roughMatch(String fact, List<String> edited, int topN) {
        List<String> keys = keysOf(fact);
        return edited.stream()
                .map(s -> Map.entry(s, keys.stream()
                        .filter(k -> s.toLowerCase().contains(k)).count()))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String renderState(String fact, List<String> candidates) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Edit fidelity check.\n");
        sb.append("original_sentence: ").append(san.text(fact, 300)).append('\n');
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("edited_candidate[").append(i + 1).append("]: ")
                    .append(san.text(candidates.get(i), 300)).append('\n');
        }
        return sb.toString();
    }

    private String renderMetaState(List<FidelityReport.FactVerdict> vs) {
        long preserved = vs.stream().filter(v -> "PRESERVED".equals(v.verdict())).count();
        long equivalent = vs.stream().filter(v -> "EQUIVALENT".equals(v.verdict())).count();
        long drift = vs.stream().filter(v -> "DRIFT".equals(v.verdict())).count();
        long lost = vs.stream().filter(v -> "LOST".equals(v.verdict())).count();
        long suspected = vs.stream().filter(v -> "SUSPECTED_LOST".equals(v.verdict())).count();
        return "Overall fidelity assessment.\nfacts_total: " + vs.size()
                + "\npreserved: " + preserved + "\nequivalent: " + equivalent
                + "\ndrift: " + drift + "\nlost: " + lost + "\nsuspected_lost: " + suspected + '\n';
    }

    public boolean isMockMode() { return jev.isMock(); }
}
