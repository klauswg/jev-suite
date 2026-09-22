# jev-proof

**Acceptance checking for sponsored creator content — Jev (TypeSafe System One) judges whether the video actually delivered the brief; deterministic code keeps the veto.**

[中文 README](README.zh-CN.md)

A brand sends a brief with checkable points ("mention the 30-day money-back guarantee", "disclose the sponsorship in the first 30 seconds"). A creator delivers a video. Today someone watches it end-to-end and ticks boxes by hand. jev-proof answers: *given the brief and the video's transcript, which points were fulfilled, was the sponsorship disclosed, and what needs fixing?* The semantic judgments come from Jev; **the evidence selection, the gate thresholds, and the final status stay in Java** — unit-testable, auditable, tunable.

## Why this architecture

- **Jev judges, code gates.** Each brief point gets a Noul question ("the transcript evidence explicitly fulfills this point") with only the relevant transcript windows as state. The gate (pHigh=0.80 / pLow=0.20 / cHigh=0.70) turns gray-zone answers into `HUMAN_REVIEW` instead of a coin flip. On our 60-sample calibration set: **Jev alone went 80/80 against labels; the gated pipeline went 68/68 with 12 designed abstentions** — zero wrong verdicts shipped.
- **No evidence, no pass.** A keyword-overlap coarse filter picks candidate transcript windows per point. If nothing surfaces, the point fails closed — the model is never asked to judge evidence it cannot see, and never gets to invent it.
- **Disclosure is a one-vote veto.** `missing` or `misplaced` sponsorship disclosure forces `NEEDS_FIX` regardless of how well the points landed.
- **Keyword matching alone is not enough — measured, not asserted.** On the same labels, a keyword baseline hits 61% accuracy with 31 false positives (everything topic-adjacent looks fulfilled). The calibration report ships in the repo.
- **Degrade to REVIEW, never to pass.** Upstream timeout/5xx/429 → the point is marked `REVIEW`. Better to annoy a human than to auto-pass a broken check.

## Architecture

```
video URL
    │
    ▼
SubtitleFetcher ── yt-dlp (external binary) → VTT → dedup ASR repeats → merge ~45s windows
    │  (no subtitles / video unavailable → UNVERIFIABLE, no model call)
    ▼
roughMatch ── keyword overlap picks top-5 candidate windows per brief point (pure code, cost control)
    │
    ▼
ProofService ── per point: Noul question over evidence windows (sanitized)
    │          ── meta: disclosure (Choice) + quality (Score) over the first 90s
    │           failure → point REVIEW (degraded)
    ▼
NoulGate ── PASS / FAIL / REVIEW per point
    ▼
overall ── disclosure veto → points rollup → PASS / NEEDS_FIX / HUMAN_REVIEW
```

## Quickstart

Requires JDK 17+, Maven, and [yt-dlp](https://github.com/yt-dlp/yt-dlp) on PATH (or set `YTDLP_PATH`).

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-proof -am package
java -jar jev-proof/target/jev-proof-0.1.0.jar   # binds 127.0.0.1:8081

# second terminal — no API key needed, mock mode answers from fixtures:
curl -X POST http://127.0.0.1:8081/v1/accept -H "Content-Type: application/json" -d '{
  "brandName": "Acme", "productName": "Acme Blender",
  "points": [{"id":"p1","text":"Introduces the Acme Blender by name","mustHave":true}],
  "disclosureRequirement": "Say \"this video is sponsored\" within the first 30 seconds",
  "videoUrl": "https://www.youtube.com/watch?v=iG9CE55wbtY"
}'
```

## Real mode

```bash
cp .env.example .env   # TYPESAFE_API_KEY=..., YTDLP_PATH=...
java -jar jev-proof/target/jev-proof-0.1.0.jar
```

## Calibration (M3, 2026-09-23)

60 labeled samples: 10 real public videos (transcript snapshotted, labels hand-verified against the transcript) + 40 synthetic (paraphrase-fulfilled / topic-adjacent / disclosure variants / strict-partial) + 10 prompt-injection cases. Point-level, 80 labels:

| column | rule | acc | precision | recall | abstain |
|---|---|---|---|---|---|
| keyword baseline | any point keyword (>3 chars) in transcript | 0.613 | 0.603 | 1.000 | 0 |
| Jev alone | noul ≥ 0.5 | **1.000** | **1.000** | **1.000** | 0 |
| combined (shipped) | gated; REVIEW = abstain | **1.000** (68/68) | **1.000** | **1.000** | 12 (15%) |

Disclosure: 55/58 (all 3 misses were disclosures buried past the 90s head window — both `missing` and `misplaced` map to NEEDS_FIX, so the product outcome is unchanged). Injection: **0/10 verdicts flipped**. 82k input tokens total, 0 degraded calls. Full data: [docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/).

Reproduce (mock mode hard-fails by design; `--allow-mock` watermarks output):

```bash
java -Dloader.main=com.jevsuite.proof.eval.EvalRunner \
  -cp jev-proof/target/jev-proof-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 60
```

## Limitations

1. **yt-dlp is a hard external dependency.** Direct YouTube timedtext fetching is dead (poToken enforcement, verified 2026-09-22). If yt-dlp breaks against a future YouTube change, subtitle intake breaks with it.
2. **Text-only judgment.** We evaluate the transcript, not the video: on-screen demos, tone, and ASR subtitle errors are out of scope.
3. **Disclosure check reads the first 90 seconds only.** A deliberately late disclosure is caught (as `missing` → NEEDS_FIX) but not distinguished from absent.
4. **Calibration set is small and partly synthetic** (60 samples, 2/3 synthetic, single annotator for real labels, English talks only). The 100% figures describe this set, not a universal accuracy claim — the abstention gate exists precisely for out-of-distribution inputs.
5. **Demo-grade API.** Binds 127.0.0.1, in-memory report store, no auth — put it behind your own gateway for anything real.
6. **Acceptance is a human call.** Output statuses are review aids, not legal/compliance rulings.

## License

MIT
