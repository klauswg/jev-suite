# jev-fidelity

**Edit-fidelity checking between an original and its edited version — Jev (TypeSafe System One) classifies every fact unit as preserved / equivalent / drift / lost; deterministic code keeps the gate.**

[中文 README](README.zh-CN.md)

Paste the original and the edited text. Get a **fact-by-fact** fidelity report with anchors on both sides. The positioning is fixed and honest: *we don't judge whether the original is true — we judge whether the edit changed it.*

## Why this architecture

- **Jev classifies, code gates.** Each original-side fact unit gets one Jev call with two questions — Noul ("is this a checkable fact at all?") + Choice (preserved / equivalent / drift / lost). Choices below 0.70 confidence become `REVIEW` instead of a shipped verdict. On 55 calibrated samples (110 fact labels): **92 judged, 1 wrong, 17 designed abstentions.**
- **No candidate, no verdict.** If keyword alignment finds zero edited-side candidates, the fact is marked `SUSPECTED_LOST` — never `LOST` — without a model call. Alignment errors must not cascade into judgments.
- **Similarity baselines miss the point — measured.** Number flips (34% → 60%) and negation flips ("did not approve" → "approved") score ~0.9 keyword overlap. The similarity baseline catches 20% of defects; the gated pipeline catches 100% with 0.975 precision.
- **Opinions are filtered, and that has a cost — documented.** `is_fact` filtering can swallow a deletion of an opinion sentence (found on real data, kept in the residual-issue list).
- **Degrade to human review, never to pass.** Model unreachable → `REVIEW` → `NEEDS_HUMAN_REVIEW`.

## Architecture

```
original text + edited text
    │
    ▼
TextSplitter ── EN + 中文 sentence splitting (decimals/abbreviations safe)
    │  (either side empty → UNPARSEABLE, no model call)
    ▼
alignment ── keyword + Chinese-bigram overlap, top-3 edited candidates per fact (pure code)
    │  zero candidates → SUSPECTED_LOST (no model call)
    ▼
Jev ── per fact: Noul is_fact + Choice fidelity
    │   edited-only sentences → Noul needs_source (cost-capped at 5)
    ▼
confidence gate ── <0.70 → REVIEW
    ▼
overall ── any DRIFT/LOST → NOT_FAITHFUL · any REVIEW/SUSPECTED_LOST → NEEDS_HUMAN_REVIEW
```

## Quickstart

Requires JDK 17+ and Maven.

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-fidelity -am package
java -jar jev-fidelity/target/jev-fidelity-0.1.0.jar   # binds 127.0.0.1:8083

# second terminal — no API key needed, mock mode answers from fixtures:
curl -X POST http://127.0.0.1:8083/v1/check -H "Content-Type: application/json" -d '{
  "originalText": "Revenue grew 40 percent in 2024. The company hired 200 engineers.",
  "editedText": "Revenue grew 60 percent in 2024. The company hired 200 engineers."
}'
```

## Real mode

```bash
cp .env.example .env   # TYPESAFE_API_KEY=...
java -jar jev-fidelity/target/jev-fidelity-0.1.0.jar
```

## Calibration (M3, 2026-09-23)

55 samples / 110 fact labels: 30 facts from **real Wikipedia revision diffs** (Bitcoin, Ethereum, Artificial intelligence, Elon Musk) + 60 constructed (number flips, negation flips, entity/qualifier drift, loss) + 20 injection. Strict labeling convention: added/changed substantive detail = drift; formatting/synonyms don't count.

| column | rule | acc | precision | recall | abstain |
|---|---|---|---|---|---|
| similarity baseline | key overlap < 0.5 → defect | 0.706 | 1.000 | 0.200 | 0 |
| Jev alone | raw choice | 0.927 | 0.833 | 1.000 | 0 |
| combined (shipped) | conf ≥ 0.70 gate | **0.989** (91/92) | **0.975** | **1.000** | 17 (15%) |

Injection: **0/20 verdicts flipped**. The convention finding (lenient vs strict labeling moved REAL accuracy 0.55 → 0.94) and both raw datasets ship in the repo: [docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/).

Reproduce (mock mode hard-fails by design; `--allow-mock` watermarks output):

```bash
java -Dloader.main=com.jevsuite.fidelity.eval.EvalRunner \
  -cp jev-fidelity/target/jev-fidelity-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 55
```

## Limitations

1. **Fidelity ≠ fact-checking.** Errors in the original are out of scope by design.
2. **Convention-dependent.** What counts as "drift" is an editorial agreement; this tool uses the strict convention and says so. Align your convention before trusting the output.
3. **Real samples are English Wikipedia revisions only**, single annotator; Chinese editing workflows, press releases, and self-media rewrites uncovered.
4. **Opinion filtering can swallow deletions** (a removed quote is excluded as non-fact).
5. **Long documents (>5,000 chars) unverified** — split first.
6. **Jev confidence fluctuates ±0.1 between runs**; borderline verdicts may switch sides. The gate exists for exactly this.
7. **Not a legal compliance certificate.**

## License

MIT
