package com.jevsuite.fit.service;

import com.jevsuite.fit.domain.Requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JD 文本 → 要求条目。
 * 启发式：跳过标题/小节头（短行、冒号结尾）；section 感知——进入
 * 「任职要求/Requirements/Qualifications」小节后条目全收（v0.1.1 修复：
 * 「具备 Owner 意识」类无信号词的要求曾被漏掉）；其余区域只保留带
 * 「要求信号词」的条目（years/experience/熟悉/具备…能力 等）——职责描述不是要求。
 * 含 preferred/plus/优先/加分 → nice-to-have；默认 must-have（保守，宁严勿宽）。
 * 条目数超过 20 时截断（控成本，PRD §3）。
 */
public final class JdParser {

    private JdParser() {}

    private static final Pattern NICE = Pattern.compile(
            "preferred|nice[- ]to[- ]have|a plus|bonus|优先|加分|者优先",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQ_SIGNAL = Pattern.compile(
            "years?|experience|degree|bachelor|master|mba|phd|skill|familiar|proficient|"
                    + "knowledge|understanding|ability|must|required|certif|"
                    + "熟悉|经验|优先|必须|掌握|精通|具备|能力|年以上|学历|证书",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQ_HEADER = Pattern.compile(
            "任职要求|岗位要求|任职资格|职位要求|requirements?|qualifications?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DUTY_HEADER = Pattern.compile(
            "职责|responsibilit|工作内容|岗位描述", Pattern.CASE_INSENSITIVE);
    /** 职责条目开头动词（在小节识别失效时兜底，防止「负责…能力建设」被当要求）。 */
    private static final Pattern DUTY_VERB = Pattern.compile(
            "^(负责|参与|推进|实践|主导|协助|独立|与|lead|own|drive|build|design|maintain)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^[•\\-*·▪►]+\\s*|^\\d+[.、)]\\s*");

    public static List<Requirement> parse(String jdText) {
        List<Requirement> out = new ArrayList<>();
        if (jdText == null) return out;
        String[] lines = jdText.split("\\R");
        boolean inReqSection = false;
        int n = 0;
        for (String raw : lines) {
            String t = BULLET.matcher(raw.trim()).replaceFirst("")
                    .replaceAll("\\s+", " ");
            boolean header = t.endsWith(":") || t.endsWith("：") || t.length() < 12;
            if (header) {
                if (REQ_HEADER.matcher(t).find()) inReqSection = true;
                else if (DUTY_HEADER.matcher(t).find()) inReqSection = false;
                continue;   // 小节头不进判定矩阵
            }
            if (t.length() > 400) continue;
            if (!inReqSection && DUTY_VERB.matcher(t).find()) continue;    // 职责条目
            if (!inReqSection && !REQ_SIGNAL.matcher(t).find()) continue;  // 非要求句
            boolean nice = NICE.matcher(t).find();
            out.add(new Requirement("r" + (++n), t, !nice));
            if (out.size() >= 20) break;
        }
        return out;
    }
}
