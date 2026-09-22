package com.jevsuite.proof.domain;

import java.util.List;

/** 品牌方 brief（要点 = 逐条可核验要求）。 */
public record Brief(
        String brandName,
        String productName,
        List<Point> points,
        String disclosureRequirement  // 例："口播前 30 秒内说出 'sponsored by X'"
) {
    public record Point(String id, String text, boolean mustHave) {}
}
