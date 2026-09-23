# jev-suite

**Four decision-quality tools built on Jev (TypeSafe System One), one shared kernel — Jev answers structured questions; deterministic code keeps the final say.**

[中文 README](README.zh-CN.md)

Jev is a decision model that answers typed questions (score / choice / noul) over a state. Each app in this suite takes one real-world "did it actually happen as required?" problem, feeds Jev sanitized evidence, and keeps the thresholds, vetoes, and routing in plain Java.

## Apps

| app | question it answers | status |
|---|---|---|
| [jev-proof](jev-proof/) | Did the sponsored video actually deliver the brief? (creator content acceptance) | ✅ v0.1.0 — 60-sample calibration: gated pipeline 68/68 correct, 12 designed abstentions, 0/10 injection flips |
| [jev-fit](jev-fit/) | Where does this candidate actually fall short of the job requirements? | ✅ v0.1.0 — 60-sample calibration: gated pipeline 115/115 correct, 5 designed abstentions, 0/20 injection flips |
| jev-fidelity | Did the edit preserve the information in the original? | ✅ v0.1.0 — 55-sample/110-fact calibration: 91/92 gated judgments correct, 17 designed abstentions, 0/20 injection flips |
| jev-rental | What must be confirmed with the listing agent before viewing? | ✅ v0.1.0 — 50-sample/178-claim calibration: gated 0.910 acc vs 0.854 raw, 0/10 injection flips |

## Shared kernel: jev-kit

- `client/` — `JevClient` interface + typed answers (`ScoreAnswer` / `ChoiceAnswer` / `NoulAnswer`), TypeSafe HTTP client (429/5xx/timeout-aware), keyword-fixture mock for offline dev
- `gate/NoulGate` — three-way gate: high-confidence pass / confident fail / everything else to human review
- `state/ExternalStringSanitizer` — prompt-injection defense for any text that came from outside

Design rules every app follows:

1. **Jev judges, code decides.** Thresholds, routing matrices, and veto rules live in unit-tested code.
2. **Degrade toward caution.** Model unreachable → review/escalate, never auto-pass.
3. **Evals refuse mock data.** Calibration runners hard-fail (`exit 2`) if the API key is missing; `--allow-mock` watermarks output.
4. **Evidence before judgment.** No evidence retrieved → the check fails closed; the model never invents grounds.

## Build

Requires JDK 17+ and Maven. jev-proof additionally needs [yt-dlp](https://github.com/yt-dlp/yt-dlp).

```bash
mvn package
```

## License

MIT
