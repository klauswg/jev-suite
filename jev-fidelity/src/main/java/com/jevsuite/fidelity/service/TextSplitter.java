package com.jevsuite.fidelity.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 中英文分句：按句末标点切，过滤过短碎片。MVP 同语言（跨语言在 V2）。
 */
public final class TextSplitter {

    private TextSplitter() {}

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String normalized = text.replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            sb.append(c);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？'
                    || c == ';' || c == '；') {
                // 小数点/缩写不切断：'.' 后必须跟空格或结尾
                if (c == '.' && i + 1 < normalized.length()
                        && normalized.charAt(i + 1) != ' ') {
                    continue;
                }
                flush(out, sb);
            }
        }
        flush(out, sb);
        return out;
    }

    private static void flush(List<String> out, StringBuilder sb) {
        String t = sb.toString().trim();
        if (t.length() >= 8) out.add(t);   // 太短的不是事实单元
        sb.setLength(0);
    }
}
