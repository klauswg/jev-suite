package com.jevsuite.fit.service;

import com.jevsuite.fit.domain.EvidenceItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 简历文本 → 证据条目。MVP 只接文本（PDF 解析在 V2）：
 * 按行切分，去空行/去项目符号，保留行号锚点（报告里每个判定可回溯到简历原文）。
 */
public final class ResumeParser {

    private ResumeParser() {}

    public static List<EvidenceItem> parse(String resumeText) {
        List<EvidenceItem> out = new ArrayList<>();
        if (resumeText == null) return out;
        String[] lines = resumeText.split("\\R");
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim()
                    .replaceFirst("^[•\\-*·▪►]+\\s*", "")
                    .replaceAll("\\s+", " ");
            if (t.length() < 4) continue;   // 太短的行（姓名/分隔符）不是证据
            out.add(new EvidenceItem("e" + (++n), i + 1, t));
        }
        return out;
    }
}
