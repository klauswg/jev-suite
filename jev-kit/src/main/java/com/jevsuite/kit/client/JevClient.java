package com.jevsuite.kit.client;

import java.util.Map;

public interface JevClient {
    /** state + 问题 schema（含 type/instructions/criteria）→ 类型化答案。 */
    JevResponse evaluate(String state, Map<String, Object> questions);
    boolean isMock();
}
