package com.jevsuite.fit.service;

import com.jevsuite.kit.client.Answer;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.JevResponse;
import com.jevsuite.kit.client.TypeSafeJevClient.JevUnavailableException;
import com.jevsuite.kit.gate.NoulGate;
import com.jevsuite.kit.state.ExternalStringSanitizer;
import com.jevsuite.fit.domain.EvidenceItem;
import com.jevsuite.fit.domain.FitReport;
import com.jevsuite.fit.domain.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 差距分析主链路（jev-fit PRD §3）：
 * 逐要求粗筛简历证据 → 一次 Jev 调用两问（Noul 是否满足 + Choice 差距类型）
 * → 门控（≥0.75 已满足 / ≤0.30 差距 / 中间存疑）→ 汇总。
 * 伦理红线：硬技能缺失只标差距，绝不生成改写建议（不虚构经历）；
 * 仅「表述不匹配且有证据」（noul ≥ 0.6）标 rewriteEligible，由用户审批。
 */
@Service
public class FitService {

    private static final Logger log = LoggerFactory.getLogger(FitService.class);

    private final JevClient jev;
    private final ExternalStringSanitizer san = new ExternalStringSanitizer();
    private final NoulGate gate = new NoulGate(0.75, 0.30, 0.70);  // PRD §3 阈值

    public FitService(JevClient jev) {
        this.jev = jev;
    }

    /** API 入口：原始文本。 */
    public FitReport analyze(String resumeText, String jdText) {
        List<Requirement> reqs = JdParser.parse(jdText);
        if (reqs.isEmpty()) {
            return new FitReport(UUID.randomUUID().toString().substring(0, 8),
                    "UNPARSEABLE_JD", null, List.of(), 0, false);
        }
        return analyzeItems(ResumeParser.parse(resumeText), reqs);
    }

    /** 核心判定（public：eval 直接注入条目，绕开解析层）。 */
    public FitReport analyzeItems(List<EvidenceItem> items, List<Requirement> reqs) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long tokens = 0;
        boolean degraded = false;
        List<FitReport.RequirementVerdict> verdicts = new ArrayList<>();

        for (Requirement r : reqs) {
            List<EvidenceItem> hits = roughMatch(r.text(), items, 6);
            String state = renderReqState(r, hits);
            Map<String, Object> q = Map.of(
                    "satisfied", Map.of("type", "noul",
                            "instructions", "The resume evidence satisfies this job requirement. " +
                                    "Skill aliases and equivalent experience count; vague adjacency does not."),
                    "gap_type", Map.of("type", "choice",
                            "instructions", "If not satisfied, what kind of gap is it?",
                            "criteria", Map.of(
                                    "satisfied", "requirement is met by the evidence",
                                    "hard_skill_missing", "a named skill/tool/certification is absent",
                                    "insufficient_experience", "skill exists but depth/years fall short",
                                    "phrasing_mismatch", "evidence exists but uses different vocabulary",
                                    "overqualified", "candidate clearly exceeds the requirement")));
            try {
                JevResponse r0 = jev.evaluate(state, q);
                tokens += r0.inputTokens();
                Answer.NoulAnswer n = (Answer.NoulAnswer) r0.answers().get("satisfied");
                String gapType = ((Answer.ChoiceAnswer) r0.answers().get("gap_type")).choice();
                NoulGate.Verdict v = gate.judge(n);
                String verdict = switch (v) {
                    case PASS -> "SATISFIED";
                    case FAIL -> "GAP";
                    case REVIEW -> "UNCERTAIN";
                };
                boolean rewrite = "phrasing_mismatch".equals(gapType) && n.value() >= 0.6;
                verdicts.add(new FitReport.RequirementVerdict(r.id(), san.text(r.text(), 200),
                        r.mustHave(), verdict, n.value(), n.derivedConfidence(), gapType, rewrite,
                        hits.stream().map(e -> new FitReport.EvidenceRef(e.lineNo(),
                                san.text(e.text(), 160))).toList()));
            } catch (JevUnavailableException e) {
                degraded = true;
                verdicts.add(new FitReport.RequirementVerdict(r.id(), san.text(r.text(), 200),
                        r.mustHave(), "UNCERTAIN", -1, -1, "unknown", false, List.of()));
            }
        }

        // 汇总：整体匹配度 5 档（一次元调用）
        Integer fitBand = null;
        try {
            JevResponse meta = jev.evaluate(renderMetaState(verdicts), Map.of(
                    "fit_band", Map.of("type", "score",
                            "instructions", "Overall candidate-JD fit.",
                            "criteria", List.of("poor: most must-haves unmet",
                                    "weak: several gaps", "fair: minor gaps",
                                    "good: must-haves met", "excellent: exceeds requirements"))));
            tokens += meta.inputTokens();
            fitBand = ((Answer.ScoreAnswer) meta.answers().get("fit_band")).ordinal();
        } catch (JevUnavailableException e) {
            degraded = true;
        }

        return new FitReport(runId, overall(verdicts, degraded), fitBand, verdicts, tokens, degraded);
    }

    private String overall(List<FitReport.RequirementVerdict> vs, boolean degraded) {
        if (degraded || vs.stream().anyMatch(v -> "UNCERTAIN".equals(v.verdict()))) {
            return "NEEDS_HUMAN_REVIEW";
        }
        if (vs.stream().anyMatch(v -> v.mustHave() && "GAP".equals(v.verdict()))) {
            return "BLOCKED_BY_MUST_HAVE";
        }
        if (vs.stream().anyMatch(v -> "GAP".equals(v.verdict()))) return "APPLY_WITH_GAPS";
        return "STRONG_MATCH";
    }

    /** 虚词表：不参与重叠计数（否则 "with" 之类会让无关证据命中粗筛）。 */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "with", "from", "that", "this", "these", "those", "will", "would", "should",
            "shall", "must", "have", "been", "more", "than", "into", "over", "such",
            "when", "what", "which", "where", "also", "very", "much", "many", "about",
            "after", "before", "between", "both", "each", "other", "some", "only",
            "same", "then", "there", "here", "under", "using", "used");

    /**
     * 证据选取（控成本但不漏判）：
     * 关键词重叠排序取前 6；重叠命中不足 6 条时按简历原顺序补齐——
     * M3 首轮实测：纯关键词粗筛遇到别名（RocketMQ↔message queue）会零命中，
     * 模型看不到证据只能 FAIL，宁可多喂也不能漏喂。
     */
    static List<EvidenceItem> roughMatch(String reqText, List<EvidenceItem> items, int topN) {
        List<String> keys = java.util.Arrays.stream(reqText.toLowerCase().split("[^a-z0-9\\p{IsHan}]+"))
                .filter(w -> w.length() > 3 && !STOPWORDS.contains(w)).toList();
        List<EvidenceItem> ranked = items.stream()
                .map(e -> Map.entry(e, keys.stream()
                        .filter(k -> e.text().toLowerCase().contains(k)).count()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        List<EvidenceItem> out = new ArrayList<>(ranked.stream()
                .filter(e -> keys.stream().anyMatch(k -> e.text().toLowerCase().contains(k)))
                .limit(topN).toList());
        for (EvidenceItem e : ranked) {          // 重叠不足 → 按原顺序补齐到 topN
            if (out.size() >= topN) break;
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    private String renderReqState(Requirement r, List<EvidenceItem> hits) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Candidate-job fit check.\n");
        sb.append("job_requirement: ").append(san.text(r.text(), 250)).append('\n');
        sb.append("requirement_level: ").append(r.mustHave() ? "must_have" : "nice_to_have").append('\n');
        if (hits.isEmpty()) {
            sb.append("resume_evidence: none found\n");
        } else {
            for (EvidenceItem e : hits) {
                sb.append("evidence[line ").append(e.lineNo()).append("]: ")
                        .append(san.text(e.text(), 300)).append('\n');
            }
        }
        return sb.toString();
    }

    private String renderMetaState(List<FitReport.RequirementVerdict> vs) {
        long sat = vs.stream().filter(v -> "SATISFIED".equals(v.verdict())).count();
        long gap = vs.stream().filter(v -> "GAP".equals(v.verdict())).count();
        long mustGap = vs.stream().filter(v -> v.mustHave() && "GAP".equals(v.verdict())).count();
        return "Overall fit assessment.\nrequirements_total: " + vs.size()
                + "\nsatisfied: " + sat + "\ngaps: " + gap
                + "\nmust_have_gaps: " + mustGap + '\n';
    }

    public boolean isMockMode() { return jev.isMock(); }
}
