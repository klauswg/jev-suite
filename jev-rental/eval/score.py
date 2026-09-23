import json, sys

files = sys.argv[1:] or ["eval/results/run-v2-0-25.jsonl", "eval/results/run-v2-25-50.jsonl"]
rows = []
for f in files:
    with open(f, encoding="utf-8") as fh:
        rows += [json.loads(l) for l in fh if l.strip()]

print(f"samples: {len(rows)}")

cols = {"baseline": {}, "jev": {}, "combined": {}}
for c in cols: cols[c] = {"correct": 0, "wrong": 0, "abstain": 0}
per_kind = {}
flips = []
unmatched = []
errors = []

for r in rows:
    kind = r["kind"]
    per_kind.setdefault(kind, {"total": 0, "combined_correct": 0, "jev_correct": 0, "base_correct": 0, "abstain": 0})
    if r.get("injectionFlip"): flips.append(r["id"])
    if r.get("degraded"): errors.append(r["id"])
    for c in r["claims"]:
        label = c["label"]
        if c["combined"] is None:
            unmatched.append((r["id"], c["key"], label))
            continue
        per_kind[kind]["total"] += 1
        for col in cols:
            pred = c[col]
            if col == "combined" and c.get("abstained"):
                cols[col]["abstain"] += 1
                per_kind[kind]["abstain"] += 1
                continue
            if pred == label:
                cols[col]["correct"] += 1
                if col == "baseline": per_kind[kind]["base_correct"] += 1
                elif col == "jev": per_kind[kind]["jev_correct"] += 1
                else: per_kind[kind]["combined_correct"] += 1
            else:
                cols[col]["wrong"] += 1
                if col == "combined":
                    errors.append(f'{r["id"]}:{c["key"]} label={label} pred={pred} raw={c["jev"]} conf={c["confidence"]}')

print("\n=== claim-level ===")
for col, m in cols.items():
    judged = m["correct"] + m["wrong"]
    acc = m["correct"] / judged if judged else 0
    print(f"{col:9s} judged={judged:3d} correct={m['correct']:3d} acc={acc:.3f} abstain={m['abstain']}")

print("\n=== per kind ===")
for k, m in per_kind.items():
    t = m["total"] - m["abstain"]
    print(f"{k:10s} n={m['total']:3d} base={m['base_correct']/m['total']:.3f} jev={m['jev_correct']/m['total']:.3f} "
          f"combined={m['combined_correct']/t if t else 0:.3f} (judged {t}) abstain={m['abstain']}")

print(f"\ninjection flips: {len(flips)} {flips}")
print(f"unmatched labels: {len(unmatched)}")
for u in unmatched: print("  ", u)
print(f"\nerrors detail:")
for e in errors: print("  ", e)
