package com.jevsuite.kit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * TypeSafe Jev 真实客户端（泛化自 jev-guard，实测形状见 jev-guard docs/05-m1-findings.md）。
 * 错误分流：429 读 retry-after（等不起直接抛降级）；超时/5xx 退避重试 1 次；其他 4xx 不重试。
 */
public class TypeSafeJevClient implements JevClient {

    private static final Logger log = LoggerFactory.getLogger(TypeSafeJevClient.class);
    private static final long MAX_RETRY_AFTER_MS = 5000;

    private final RestClient http;
    private final ObjectMapper om = new ObjectMapper();
    private final String model;

    public TypeSafeJevClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.model = model;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutMs);
        rf.setReadTimeout(timeoutMs);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public JevResponse evaluate(String state, Map<String, Object> questions) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", questions);

        long t0 = System.nanoTime();
        boolean retried = false;
        try {
            JsonNode resp = post(body);
            return parse(resp, questions, elapsed(t0), false);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                long wait = parseRetryAfter(e);
                if (wait > 0 && wait <= MAX_RETRY_AFTER_MS) {
                    sleep(wait);
                    return parse(post(body), questions, elapsed(t0), true);
                }
                throw new JevUnavailableException("429 rate limited, retry-after=" + wait + "ms", e);
            }
            if (status >= 500) {
                retried = true;
                sleep(500);
                try {
                    return parse(post(body), questions, elapsed(t0), true);
                } catch (Exception e2) {
                    throw new JevUnavailableException("5xx after retry", e2);
                }
            }
            throw new JevUnavailableException("4xx " + status + ": " + e.getResponseBodyAsString(), e);
        } catch (JevUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (!retried) {
                sleep(500);
                try {
                    return parse(post(body), questions, elapsed(t0), true);
                } catch (Exception e2) {
                    throw new JevUnavailableException("timeout after retry", e2);
                }
            }
            throw new JevUnavailableException("call failed", e);
        }
    }

    private long elapsed(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    private JsonNode post(Map<String, Object> body) {
        try {
            String resp = http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(om.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return om.readTree(resp);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (Exception e) {
            throw new JevUnavailableException("http error", e);
        }
    }

    /** 按 questions 里声明的 type 逐题解析（score 0 基、noul 派生信度——实测形状）。 */
    JevResponse parse(JsonNode resp, Map<String, Object> questions, long latencyMs, boolean retried) {
        JsonNode answersNode = resp.path("answers");
        Map<String, Answer> out = new HashMap<>();
        for (Map.Entry<String, Object> q : questions.entrySet()) {
            String name = q.getKey();
            String type = q.getValue() instanceof Map<?, ?> m ? String.valueOf(m.get("type")) : "";
            JsonNode a = answersNode.path(name);
            switch (type) {
                case "choice" -> {
                    Double conf = a.has("confidence") ? a.get("confidence").asDouble() : null;
                    out.put(name, new Answer.ChoiceAnswer(a.path("choice").asText("unknown"), conf));
                }
                case "score" -> {
                    int scale = q.getValue() instanceof Map<?, ?> m && m.get("criteria") instanceof java.util.List<?> l
                            ? l.size() : 5;
                    int idx = argmax(a.path("probabilities"));
                    if (idx < 0) idx = (int) Math.round(a.path("score").asDouble(0));
                    Double conf = a.has("confidence") ? a.get("confidence").asDouble() : null;
                    out.put(name, new Answer.ScoreAnswer(idx, scale, conf));
                }
                case "noul" -> out.put(name, Answer.NoulAnswer.of(a.path("noul").asDouble(0.0)));
                default -> log.warn("unknown question type '{}' for '{}', skipped", type, name);
            }
        }
        long inTok = resp.path("usage").path("input_tokens").asLong(0);
        return new JevResponse(out, inTok, latencyMs, retried);
    }

    private int argmax(JsonNode probs) {
        if (!probs.isObject() || probs.isEmpty()) return -1;
        int best = -1;
        double bestV = -1;
        for (Iterator<Map.Entry<String, JsonNode>> it = probs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            double v = en.getValue().asDouble();
            if (v > bestV) {
                bestV = v;
                try { best = Integer.parseInt(en.getKey()); } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    private long parseRetryAfter(RestClientResponseException e) {
        String h = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("retry-after") : null;
        if (h == null) return -1;
        try { return Long.parseLong(h.trim()) * 1000; } catch (NumberFormatException ex) { return -1; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Override
    public boolean isMock() { return false; }

    /** 上游不可用：上层降级到最保守桶（宁可人审，不可漏放/误判通过）。 */
    public static class JevUnavailableException extends RuntimeException {
        public JevUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }
}
