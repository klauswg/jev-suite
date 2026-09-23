package com.jevsuite.rental.service;

import java.util.Map;

/**
 * 租金合理性内置区间表（整租一居，元/月，公开平台挂牌快照口径，非实时行情）。
 * 只做「偏离 ±40% 报警」用，不做精细定价——PRD §3 硬规则，不调 Jev 算数。
 */
public final class RentRanges {

    private RentRanges() {}

    public record Range(int min, int max) {}

    private static final Map<String, Range> TABLE = Map.of(
            "北京", new Range(3500, 8000),
            "上海", new Range(3500, 8000),
            "深圳", new Range(3000, 7000),
            "广州", new Range(2000, 5000),
            "杭州", new Range(2500, 5500),
            "成都", new Range(1500, 4000),
            "武汉", new Range(1500, 4000),
            "南京", new Range(2000, 5000),
            "西安", new Range(1500, 3500),
            "重庆", new Range(1500, 3500));

    private static final Range DEFAULT = new Range(1000, 5000);

    public static Range of(String city) {
        if (city == null) return DEFAULT;
        for (Map.Entry<String, Range> e : TABLE.entrySet()) {
            if (city.contains(e.getKey())) return e.getValue();
        }
        return DEFAULT;
    }

    /** 偏离区间 ±40% 视为异常（双向：过低疑似引流，过高提醒比价）。 */
    public static boolean isOutlier(String city, int rent) {
        Range r = of(city);
        return rent < r.min() * 0.6 || rent > r.max() * 1.4;
    }

    public static String describe(String city) {
        Range r = of(city);
        return r.min() + "-" + r.max() + " 元/月（内置快照，非实时行情）";
    }
}
