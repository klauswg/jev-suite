package com.jevsuite.rental.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 房源描述分句（纯代码）：中英文标点 + 换行切分，
 * 过滤纯空白/纯符号行。小数点/缩写不做特殊保护——房源文本几乎不出现，
 * 误切一句的代价是清单多一条，可接受（PRD MVP 原则）。
 */
public final class ListingParser {

    private ListingParser() {}

    public record Claim(int no, String text) {}

    public static List<Claim> parse(String text) {
        List<Claim> out = new ArrayList<>();
        if (text == null) return out;
        String[] parts = text.split("[。！？!?；;\\n]+|(?<=[a-z0-9])\\.\\s+");
        int no = 1;
        for (String p : parts) {
            String t = p.trim();
            if (t.length() < 2) continue;
            if (t.chars().noneMatch(Character::isLetterOrDigit)) continue;
            out.add(new Claim(no++, t));
        }
        return out;
    }
}
