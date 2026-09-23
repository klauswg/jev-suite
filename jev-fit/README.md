# jev-fit

**Calibrated gap analysis between a resume and a job description — Jev (TypeSafe System One) judges whether your evidence satisfies each requirement; deterministic code keeps the gate.**

[中文 README](README.zh-CN.md)

Paste your resume and a JD. Get a **requirement-by-requirement** gap report: how strongly the resume evidence satisfies each JD line (with a probability), what kind of gap it is, and where a rewrite could help — every verdict anchored to your actual resume lines. **No percentage scores, no fabricated experience.**

## Why this architecture

- **Jev judges, code gates.** Each requirement gets one Jev call with two questions — Noul ("the evidence satisfies this requirement") + Choice (gap type). The gate (pHigh=0.75 / pLow=0.30 / cHigh=0.70) sends gray-zone answers to `UNCERTAIN` for human judgment. On the 60-sample calibration set: **the gated pipeline went 115/115 correct with 5 designed abstentions.**
- **Evidence recall comes first — measured, then fixed.** Round 1 evaluation showed keyword-only evidence selection starving the model (recall 0.713: your resume says RocketMQ, the JD says "message queue", zero keyword overlap). The fix: overlap-ranked top-6 with backfill. Recall went to 1.000 at +4% token cost. The failure data ships in the repo.
- **Keyword matching is the baseline we beat, not the method we use.** The same labels under a Jobscan-style keyword baseline: 77% accuracy, 21 false negatives, 7 false positives. That gap is the product.
- **Ethical red line, in code.** `hard_skill_missing` gets a learning note, never a rewrite suggestion. Only `phrasing_mismatch` with noul ≥ 0.6 is marked rewrite-eligible — and we mark the anchor lines, we don't ghostwrite. The approval is yours.
- **Degrade to human review, never to pass.** Model unreachable → `UNCERTAIN` → `NEEDS_HUMAN_REVIEW`.

## Architecture

```
resume text + JD text
    │
    ▼
ResumeParser ── lines → evidence items (line-number anchors)
JdParser     ── bullets → requirements (signal-word filter, must/nice-to-have, cap 20)
    │  (JD unparseable → error, no model call)
    ▼
evidence selection ── keyword-overlap top-6 + backfill (pure code, cost control)
    │
    ▼
Jev ── per requirement: Noul satisfied? + Choice gap type
    │   meta: overall fit band (Score 1-5, never a percentage)
    ▼
NoulGate ── SATISFIED / GAP / UNCERTAIN
    ▼
overall ── STRONG_MATCH / APPLY_WITH_GAPS / BLOCKED_BY_MUST_HAVE / NEEDS_HUMAN_REVIEW
```

## Quickstart

Requires JDK 17+ and Maven.

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-fit -am package
java -jar jev-fit/target/jev-fit-0.1.0.jar   # binds 127.0.0.1:8082

# second terminal — no API key needed, mock mode answers from fixtures:
curl -X POST http://127.0.0.1:8082/v1/analyze -H "Content-Type: application/json" -d '{
  "resumeText": "Backend engineer\n- 6 years of Java development with Spring Cloud\n- Built payment clearing services",
  "jdText": "Senior Backend Engineer\nRequirements:\n- 5+ years of backend development experience in Java\n- Experience with payment or clearing systems is required\n- Experience with React is preferred"
}'
```

## Real mode

```bash
cp .env.example .env   # TYPESAFE_API_KEY=...
java -jar jev-fit/target/jev-fit-0.1.0.jar
```

## Calibration (M3, 2026-09-23)

60 constructed samples (30 role pairs / 20 boundary cases / 10 prompt-injection), 120 requirement-level labels:

| column | rule | acc | precision | recall | abstain |
|---|---|---|---|---|---|
| keyword baseline | requirement keywords in resume | 0.767 | 0.894 | 0.738 | 0 |
| Jev alone | noul ≥ 0.5 | 0.983 | 1.000 | 0.975 | 0 |
| combined (shipped) | gated; UNCERTAIN = abstain | **1.000** (115/115) | **1.000** | **1.000** | 5 (4.2%) |

Injection: **0/20 verdicts flipped**. 81k input tokens, 0 degraded calls, ~931ms/sample. Full data: [docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/) — including the round-1 failure that motivated the evidence-selection fix.

Reproduce (mock mode hard-fails by design; `--allow-mock` watermarks output):

```bash
java -Dloader.main=com.jevsuite.fit.eval.EvalRunner \
  -cp jev-fit/target/jev-fit-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 60
```

## Limitations

1. **Agreement ≠ interview rate.** ATS behavior varies by company/system/configuration. This report measures judgment quality on constructed samples, not hiring outcomes.
2. **Text input only in MVP.** PDF parsing (two-column / image resumes) is V2.
3. **Heuristic JD parsing.** Unusually formatted JDs may lose requirement lines.
4. **Evidence backfill has a ceiling.** Very long resumes (50+ items) can still starve a requirement of its key evidence (top-6 cap).
5. **All samples are hand-constructed** (common role archetypes, single annotator). Real-application performance unverified.
6. **No ghostwriting, by design.** Rewrite suggestions mark your own evidence anchors and stop there. If you want one-click keyword stuffing, this is deliberately not that tool.
7. **Not career advice.**

## License

MIT
