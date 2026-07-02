from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def pct(v):
    return "-" if v is None else f"{v:.3f}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scores")
    parser.add_argument("--out-dir", default=None)
    args = parser.parse_args()
    scores_path = Path(args.scores)
    scores = json.loads(scores_path.read_text(encoding="utf-8"))
    out_dir = Path(args.out_dir) if args.out_dir else scores_path.with_suffix("").parent / scores_path.stem.replace("_scores", "")
    out_dir.mkdir(parents=True, exist_ok=True)
    lines = ["# RAG 评测报告", "", "## Overall", "", "| 指标 | 值 |", "| --- | --- |"]
    for k, v in scores["overall"].items():
        lines.append(f"| {k} | {pct(v)} |")
    lines += ["", "## By Intent L2", "", "| intent_l2 | intent_top1 | hit@5 | recall@5 | mrr@10 | latency_ms |", "| --- | --- | --- | --- | --- | --- |"]
    for intent, data in sorted(scores["by_intent_l2"].items()):
        lines.append(f"| {intent} | {pct(data.get('intent_top1'))} | {pct(data.get('hit@5'))} | {pct(data.get('recall@5'))} | {pct(data.get('mrr@10'))} | {pct(data.get('latency_ms'))} |")
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    with (out_dir / "per_sample.csv").open("w", encoding="utf-8-sig", newline="") as f:
        fields = ["query_id", "intent_l1", "intent_l2", "difficulty", "final_status", "intent_top1", "hit@5", "recall@5", "mrr@10", "latency_ms", "faithfulness_manual", "answer_relevancy_manual", "answer_correctness_manual", "context_precision_manual", "context_recall_manual"]
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in scores["per_sample"]:
            writer.writerow({k: row.get(k, "") for k in fields})
    failures = [r for r in scores["per_sample"] if r.get("intent_top1") == 0 or r.get("hit@5") == 0]
    with (out_dir / "failures.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for row in failures:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(out_dir)


if __name__ == "__main__":
    main()