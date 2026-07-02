from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


def rows(path: Path):
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def hit_at(retrieved, expected, k):
    if not expected:
        return None
    return 1.0 if set(retrieved[:k]) & set(expected) else 0.0


def recall_at(retrieved, expected, k):
    if not expected:
        return None
    return len(set(retrieved[:k]) & set(expected)) / len(set(expected))


def mrr_at(retrieved, expected, k):
    if not expected:
        return None
    expected = set(expected)
    for idx, doc_id in enumerate(retrieved[:k], start=1):
        if doc_id in expected:
            return 1.0 / idx
    return 0.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    records = list(rows(Path(args.run)))
    per = []
    for r in records:
        retrieved = r.get("retrieved_doc_ids") or []
        expected = r.get("expected_doc_ids") or []
        intent_ok = 1.0 if r.get("intent_pred") == r.get("intent_l2") else 0.0
        item = {"query_id": r["query_id"], "intent_l1": r["intent_l1"], "intent_l2": r["intent_l2"], "difficulty": r["difficulty"], "intent_top1": intent_ok, "hit@5": hit_at(retrieved, expected, 5), "recall@5": recall_at(retrieved, expected, 5), "mrr@10": mrr_at(retrieved, expected, 10), "latency_ms": r.get("latency_ms"), "final_status": r.get("final_status")}
        per.append(item)
    overall = {key: mean([p.get(key) for p in per]) for key in ["intent_top1", "hit@5", "recall@5", "mrr@10", "latency_ms"]}
    by_l2 = defaultdict(list)
    for p in per:
        by_l2[p["intent_l2"]].append(p)
    scores = {"overall": overall, "by_intent_l2": {k: {m: mean([p.get(m) for p in v]) for m in overall} for k, v in by_l2.items()}, "per_sample": per}
    out = Path(args.out) if args.out else Path(args.run).with_name(Path(args.run).stem + "_scores.json")
    out.write_text(json.dumps(scores, ensure_ascii=False, indent=2), encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()