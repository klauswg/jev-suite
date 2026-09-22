package com.jevsuite.kit.state;

/**
 * 外部可控字符串清洗（通用化自 jev-guard，审计 D4 同源）。
 * 字幕、简历、JD、房源描述、双文本——凡是攻击者/第三方可控的输入，
 * 进 state 前必须剥控制字符 + 截断；枚举字段不透传任意字符串。
 */
public class ExternalStringSanitizer {

    /** 通用文本：剥控制字符、折叠空白、截断（默认 500 字符）。 */
    public String text(String raw) { return text(raw, 500); }

    public String text(String raw, int max) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 标识符（token/编号类）：严格白名单。 */
    public String token(String raw, int max) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "");
        if (s.isEmpty()) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 枚举字段：只接受白名单值，其余归 unknown。 */
    public String label(String raw, String... allowed) {
        if (raw == null) return "unknown";
        for (String a : allowed) if (a.equals(raw)) return raw;
        return "unknown";
    }
}
