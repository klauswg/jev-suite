package com.jevsuite.proof.service;

import com.jevsuite.kit.client.Answer;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.JevResponse;
import com.jevsuite.kit.client.TypeSafeJevClient.JevUnavailableException;
import com.jevsuite.kit.gate.NoulGate;
import com.jevsuite.kit.state.ExternalStringSanitizer;
import com.jevsuite.proof.domain.AcceptanceResult;
import com.jevsuite.proof.domain.Brief;
import com.jevsuite.proof.subs.SubtitleFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 验收主链路（jev-proof PRD §3）：
 * 字幕 → 硬规则（无字幕/视频不可用 → UNVERIFIABLE，不调 Jev）
 * → 逐要点粗筛相关字幕段（关键词重叠，控成本）→ Jev 判定 → 门控 → 报告。
 * 失败降级：单要点失败标 REVIEW；披露不合规一票否决 NEEDS_FIX。
 */
@Service
public class ProofService {

    private static final Logger log = LoggerFactory.getLogger(ProofService.class);

    private final SubtitleFetcher subs;
    private final JevClient jev;
    private final ExternalStringSanitizer san = new ExternalStringSanitizer();
    private final NoulGate gate = new NoulGate(0.80, 0.20, 0.70); // 阈值拍定，eval 校准后人工确认

    public ProofService(SubtitleFetcher subs, JevClient jev) {
        this.subs = subs;
        this.jev = jev;
    }

    public AcceptanceResult accept(Brief brief, String videoUrl) {
        List<SubtitleFetcher.Segment> segs;
        try {
            segs = SubtitleFetcher.merge(subs.fetch(videoUrl), 45);
        } catch (SubtitleFetcher.SubtitleException e) {
            log.warn("subtitle unavailable: {}", e.getMessage());
            return new AcceptanceResult(UUID.randomUUID().toString().substring(0, 8), videoUrl,
                    "UNVERIFIABLE", "unknown", null, List.of(), 0, false);
        }

        return evaluateSegments(brief, videoUrl, segs);
    }

    /**
     * 核心判定（public：eval 合成样本直接注入字幕段，绕开 yt-dlp）。
     */
    public AcceptanceResult evaluateSegments(Brief brief, String videoUrl, List<SubtitleFetcher.Segment> segs) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long tokens = 0;
        boolean degraded = false;
        List<AcceptanceResult.PointVerdict> verdicts = new ArrayList<>();

        // 1. 逐要点：粗筛证据段 → Noul 判定
        for (Brief.Point p : brief.points()) {
            List<SubtitleFetcher.Segment> hits = roughMatch(p.text(), segs, 5);
            String state = renderPointState(brief, p, hits);
            Map<String, Object> q = Map.of("point_fulfilled", Map.of(
                    "type", "noul",
                    "instructions", "The transcript evidence explicitly fulfills the brief point. " +
                            "Vague mentions or topic adjacency do not count as fulfillment."));
            try {
                JevResponse r = jev.evaluate(state, q);
                tokens += r.inputTokens();
                Answer.NoulAnswer n = (Answer.NoulAnswer) r.answers().get("point_fulfilled");
                NoulGate.Verdict v = gate.judge(n);
                verdicts.add(new AcceptanceResult.PointVerdict(p.id(), v.name(), n.value(),
                        n.derivedConfidence(),
                        hits.stream().map(s -> new AcceptanceResult.Evidence(s.startSec(), s.endSec(),
                                san.text(s.text(), 160))).toList()));
            } catch (JevUnavailableException e) {
                degraded = true;
                verdicts.add(new AcceptanceResult.PointVerdict(p.id(), "REVIEW", -1, -1, List.of()));
            }
        }

        // 2. 披露合规（Choice，一票否决）+ 口播质量（Score）
        String disclosure = "unknown";
        Integer quality = null;
        String head = headTranscript(segs, 90); // 披露通常在前 90 秒
        Map<String, Object> q2 = new HashMap<>();
        q2.put("disclosure", Map.of("type", "choice",
                "instructions", "Does the transcript properly disclose the sponsorship as required?",
                "criteria", Map.of(
                        "compliant", "clear sponsorship disclosure matching the requirement",
                        "misplaced", "disclosure exists but late, buried, or wrong wording",
                        "missing", "no sponsorship disclosure at all")));
        q2.put("quality", Map.of("type", "score",
                "instructions", "Overall sponsored-segment quality.",
                "criteria", List.of("off_brief: ignores the brief", "poor: robotic reading",
                        "acceptable: covers the points", "good: natural integration", "excellent: compelling and on-brief")));
        try {
            JevResponse r = jev.evaluate(renderMetaState(brief, head), q2);
            tokens += r.inputTokens();
            disclosure = ((Answer.ChoiceAnswer) r.answers().get("disclosure")).choice();
            quality = ((Answer.ScoreAnswer) r.answers().get("quality")).ordinal();
        } catch (JevUnavailableException e) {
            degraded = true;
        }

        // 3. 总体结论
        String status = overall(verdicts, disclosure);

        return new AcceptanceResult(runId, videoUrl, status, disclosure, quality, verdicts, tokens, degraded);
    }

    private String overall(List<AcceptanceResult.PointVerdict> vs, String disclosure) {
        if ("missing".equals(disclosure) || "misplaced".equals(disclosure)) return "NEEDS_FIX";
        boolean anyFail = vs.stream().anyMatch(v -> "FAIL".equals(v.verdict()));
        boolean anyReview = vs.stream().anyMatch(v -> "REVIEW".equals(v.verdict()));
        if (anyFail) return "NEEDS_FIX";
        if (anyReview) return "HUMAN_REVIEW";
        return "PASS";
    }

    /** 关键词重叠粗筛（纯代码，控成本）：取重叠度最高的 topN 段。 */
    static List<SubtitleFetcher.Segment> roughMatch(String pointText,
                                                    List<SubtitleFetcher.Segment> segs, int topN) {
        List<String> keys = java.util.Arrays.stream(pointText.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 3).toList();
        return segs.stream()
                .map(s -> Map.entry(s, keys.stream().filter(k -> s.text().toLowerCase().contains(k)).count()))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String renderPointState(Brief b, Brief.Point p, List<SubtitleFetcher.Segment> hits) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Sponsored content acceptance check.\n");
        sb.append("brand: ").append(san.text(b.brandName(), 60)).append('\n');
        sb.append("product: ").append(san.text(b.productName(), 60)).append('\n');
        sb.append("brief_point: ").append(san.text(p.text(), 200)).append('\n');
        if (hits.isEmpty()) {
            sb.append("transcript_evidence: none found\n");
        } else {
            for (SubtitleFetcher.Segment s : hits) {
                sb.append("evidence[").append((int) s.startSec()).append("s]: ")
                        .append(san.text(s.text(), 600)).append('\n');
            }
        }
        return sb.toString();
    }

    private String renderMetaState(Brief b, String head) {
        return "Sponsorship disclosure and quality check.\n" +
                "brand: " + san.text(b.brandName(), 60) + '\n' +
                "disclosure_requirement: " + san.text(b.disclosureRequirement(), 200) + '\n' +
                "transcript_opening: " + san.text(head, 1200) + '\n';
    }

    private String headTranscript(List<SubtitleFetcher.Segment> segs, double uptoSec) {
        StringBuilder sb = new StringBuilder();
        for (SubtitleFetcher.Segment s : segs) {
            if (s.startSec() > uptoSec) break;
            sb.append(s.text()).append(' ');
        }
        return sb.toString().trim();
    }

    public boolean isMockMode() { return jev.isMock(); }
}
