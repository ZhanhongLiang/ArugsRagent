from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


def mean(values: list[float | None]) -> float | None:
    valid = [value for value in values if value is not None]
    return sum(valid) / len(valid) if valid else None


def percentile(values: list[float | None], quantile: float) -> float | None:
    valid = sorted(value for value in values if value is not None)
    if not valid:
        return None
    position = (len(valid) - 1) * quantile
    lower, upper = math.floor(position), math.ceil(position)
    return valid[lower] if lower == upper else valid[lower] + (valid[upper] - valid[lower]) * (position - lower)


def retrieval_metrics(retrieved: list[str], expected: list[str]) -> tuple[float | None, float | None, float | None]:
    if not expected:
        return None, None, None
    expected_set = set(expected)
    top_five = retrieved[:5]
    hit = 1.0 if expected_set.intersection(top_five) else 0.0
    recall = len(expected_set.intersection(top_five)) / len(expected_set)
    reciprocal_rank = 0.0
    for index, document_id in enumerate(retrieved[:10], start=1):
        if document_id in expected_set:
            reciprocal_rank = 1.0 / index
            break
    return hit, recall, reciprocal_rank


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    intent_ids = json.loads((ROOT / "state" / "intent_ids.json").read_text(encoding="utf-8"))
    id_to_code = {str(node_id): code for code, node_id in intent_ids.items()}
    samples = []
    for record in read_jsonl(Path(args.run)):
        predicted = id_to_code.get(str(record.get("intent_pred")), record.get("intent_pred"))
        is_rag = bool(record.get("requires_rag"))
        is_mcp = record.get("expected_answer_type") == "mcp_answer"
        is_system = record.get("expected_answer_type") == "no_rag"
        hit, recall, reciprocal_rank = retrieval_metrics(record.get("retrieved_doc_ids") or [], record.get("expected_doc_ids") or []) if is_rag else (None, None, None)
        samples.append({
            "query_id": record["query_id"],
            "intent": record["intent_l2"],
            "expected_intent": record["intent_l2"],
            "predicted_intent": predicted,
            "intent_top1": 1.0 if predicted == record["intent_l2"] else 0.0,
            "requires_rag": is_rag,
            "expected_mcp": is_mcp,
            "system_case": is_system,
            "hit_at_5": hit,
            "recall_at_5": recall,
            "mrr_at_10": reciprocal_rank,
            "kb_route_accuracy": 1.0 if record.get("has_kb") else 0.0 if is_rag else None,
            "mcp_invocation_rate": 1.0 if record.get("has_mcp") else 0.0 if is_mcp else None,
            "mcp_route_accuracy": 1.0 if record.get("has_mcp") and predicted == record["intent_l2"] else 0.0 if is_mcp else None,
            "no_retrieval_accuracy": 1.0 if not record.get("has_kb") and not record.get("has_mcp") else 0.0 if is_system else None,
            "latency_ms": record.get("latency_ms"),
            "final_status": record.get("final_status"),
        })
    metrics = ["intent_top1", "hit_at_5", "recall_at_5", "mrr_at_10", "kb_route_accuracy", "mcp_invocation_rate", "mcp_route_accuracy", "no_retrieval_accuracy", "latency_ms"]
    grouped = defaultdict(list)
    for sample in samples:
        grouped[sample["intent"]].append(sample)
    payload = {
        "run": str(Path(args.run)),
        "sample_count": len(samples),
        "overall": {
            "run_success_rate": mean([1.0 if item["final_status"] == "success" else 0.0 for item in samples]),
            "intent_top1": mean([item["intent_top1"] for item in samples]),
            "doc_hit_at_5": mean([item["hit_at_5"] for item in samples if item["requires_rag"]]),
            "doc_recall_at_5": mean([item["recall_at_5"] for item in samples if item["requires_rag"]]),
            "mrr_at_10": mean([item["mrr_at_10"] for item in samples if item["requires_rag"]]),
            "kb_route_accuracy": mean([item["kb_route_accuracy"] for item in samples if item["requires_rag"]]),
            "mcp_invocation_rate": mean([item["mcp_invocation_rate"] for item in samples if item["expected_mcp"]]),
            "mcp_route_accuracy": mean([item["mcp_route_accuracy"] for item in samples if item["expected_mcp"]]),
            "no_retrieval_accuracy": mean([item["no_retrieval_accuracy"] for item in samples if item["system_case"]]),
            "latency_ms_p50": percentile([item["latency_ms"] for item in samples], 0.5),
            "latency_ms_p95": percentile([item["latency_ms"] for item in samples], 0.95),
        },
        "by_intent": {intent: {metric: mean([item[metric] for item in items]) for metric in metrics} for intent, items in sorted(grouped.items())},
        "samples": samples,
    }
    output = Path(args.out) if args.out else Path(args.run).with_name(Path(args.run).stem + "_equipment_scores.json")
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
