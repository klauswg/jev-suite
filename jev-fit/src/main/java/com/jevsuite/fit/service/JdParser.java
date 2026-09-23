package com.jevsuite.fit.service;

import com.jevsuite.fit.domain.Requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JD 文本 → 要求条目。
 * 启发式：跳过标题/小节头（短行、冒号结尾）；只保留带「要求信号词」的条目
 * （years/experience/熟悉/精通 等）——职责描述不是要求，不进判定矩阵。
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
                    + "熟悉|经验|优先|必须|掌握|精通|年以上|学历|证书",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^[•\\-*·▪►]+\\s*|^\\d+[.、)]\\s*");

    public static List<Requirement> parse(String jdText) {
        List<Requirement> out = new ArrayList<>();
        if (jdText == null) return out;
        String[] lines = jdText.split("\\R");
        int n = 0;
        for (String raw : lines) {
            String t = BULLET.matcher(raw.trim()).replaceFirst("")
                    .replaceAll("\\s+", " ");
            if (t.length() < 12 || t.length() > 400) continue;  // 标题/整段跳过
            if (t.endsWith(":") || t.endsWith("：")) continue;    // 小节头
            if (!REQ_SIGNAL.matcher(t).find()) continue;          // 非要求句（职责描述等）
            boolean nice = NICE.matcher(t).find();
            out.add(new Requirement("r" + (++n), t, !nice));
            if (out.size() >= 20) break;
        }
        return out;
    }
}
