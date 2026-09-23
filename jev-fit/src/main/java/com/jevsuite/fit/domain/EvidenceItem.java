package com.jevsuite.fit.domain;

/** 简历证据条目（保留原文锚点 = 行号）。 */
public record EvidenceItem(String id, int lineNo, String text) {}
