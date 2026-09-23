package com.jevsuite.fit.domain;

/** JD 单条要求（已条目化）。 */
public record Requirement(String id, String text, boolean mustHave) {}
