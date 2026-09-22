package com.jevsuite.kit.client;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock 客户端：按 state 关键词匹配 fixtures，无匹配回退到类型默认值。
 * 用于无 key 跑通全流程；eval 下必须硬失败（各应用 EvalRunner 负责）。
 */
public class MockJevClient implements JevClient {

    /** 关键词 → noul 值（命中 state 中任一关键词的 noul 题用该值）。 */
    private final Map<String, Double> noulFixtures;
    private final double defaultNoul;

    public MockJevClient() {
        this(Map.of(), 0.5);
    }

    public MockJevClient(Map<String, Double> noulFixtures, double defaultNoul) {
        this.noulFixtures = noulFixtures;
        this.defaultNoul = defaultNoul;
    }

    @Override
    public JevResponse evaluate(String state, Map<String, Object> questions) {
        Map<String, Answer> out = new HashMap<>();
        for (Map.Entry<String, Object> q : questions.entrySet()) {
            if (!(q.getValue() instanceof Map<?, ?> m)) continue;
            String type = String.valueOf(m.get("type"));
            switch (type) {
                case "choice" -> {
                    Object criteria = m.get("criteria");
                    String first = criteria instanceof Map<?, ?> cm && !cm.isEmpty()
                            ? String.valueOf(cm.keySet().iterator().next()) : "unknown";
                    out.put(q.getKey(), new Answer.ChoiceAnswer(first, 0.9));
                }
                case "score" -> out.put(q.getKey(), new Answer.ScoreAnswer(1, 5, 0.9));
                case "noul" -> {
                    double v = defaultNoul;
                    for (Map.Entry<String, Double> f : noulFixtures.entrySet()) {
                        if (state.contains(f.getKey())) { v = f.getValue(); break; }
                    }
                    out.put(q.getKey(), Answer.NoulAnswer.of(v));
                }
                default -> {}
            }
        }
        return new JevResponse(out, 0, 1, false);
    }

    @Override
    public boolean isMock() { return true; }
}
