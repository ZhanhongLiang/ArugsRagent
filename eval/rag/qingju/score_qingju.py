from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as file:
        for line in file:
            if line.strip():
                yield json.loads(line)


def mean(values: list[float | None]) -> float | None:
    valid = [value for value in values if value is not None]
    return sum(valid) / len(valid) if valid else None


def percentile(values: list[float | None], percent: float) -> float | None:
    valid = sorted(value for value in values if value is not None)
    if not valid:
        return None
    position = (len(valid) - 1) * percent
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return valid[lower]
    return valid[lower] + (valid[upper] - valid[lower]) * (position - lower)


def hit_at_5(retrieved: list[str], expected: list[str]) -> float | None:
    return 1.0 if set(retrieved[:5]) & set(expected) else 0.0 if expected else None


def recall_at_5(retrieved: list[str], expected: list[str]) -> float | None:
    return len(set(retrieved[:5]) & set(expected)) / len(set(expected)) if expected else None


def mrr_at_10(retrieved: list[str], expected: list[str]) -> float | None:
    if not expected:
        return None
    expected_set = set(expected)
    for rank, doc_id in enumerate(retrieved[:10], start=1):
        if doc_id in expected_set:
            return 1.0 / rank
    return 0.0


def scoped_mean(samples: list[dict], metric: str, predicate) -> float | None:
    return mean([sample.get(metric) for sample in samples if predicate(sample)])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    intent_ids = json.loads((ROOT / "state" / "intent_ids.json").read_text(encoding="utf-8"))
    id_to_code = {str(node_id): code for code, node_id in intent_ids.items()}
    records_by_query_id = {}
    for record in read_jsonl(Path(args.run)):
        records_by_query_id[record["query_id"]] = record
    records = list(records_by_query_id.values())
    samples = []
    for record in records:
        expected_intent = record["intent_l2"]
        raw_pred = record.get("intent_pred")
        predicted_intent = id_to_code.get(str(raw_pred), raw_pred)
        expected_docs = record.get("expected_doc_ids") or []
        retrieved_docs = record.get("retrieved_doc_ids") or []
        requires_rag = bool(record.get("requires_rag"))
        expected_mcp = record.get("expected_answer_type") == "mcp_answer"
        system_case = record.get("expected_answer_type") == "no_rag"
        intent_top1 = 1.0 if predicted_intent == expected_intent else 0.0
        has_kb = bool(record.get("has_kb"))
        has_mcp = bool(record.get("has_mcp"))
        samples.append({
            "query_id": record["query_id"],
            "query": record["query"],
            "intent_l1": record["intent_l1"],
            "intent_l2": expected_intent,
            "intent_pred": predicted_intent,
            "intent_pred_raw": raw_pred,
            "difficulty": record.get("difficulty"),
            "final_status": record.get("final_status"),
            "requires_rag": requires_rag,
            "expected_mcp": expected_mcp,
            "system_case": system_case,
            "expected_doc_ids": expected_docs,
            "retrieved_doc_ids": retrieved_docs,
            "has_kb": has_kb,
            "has_mcp": has_mcp,
            "intent_top1": intent_top1,
            "hit_at_5": hit_at_5(retrieved_docs, expected_docs) if requires_rag else None,
            "recall_at_5": recall_at_5(retrieved_docs, expected_docs) if requires_rag else None,
            "mrr_at_10": mrr_at_10(retrieved_docs, expected_docs) if requires_rag else None,
            "kb_route_accuracy": (1.0 if has_kb else 0.0) if requires_rag else None,
            "mcp_invocation_rate": (1.0 if has_mcp else 0.0) if expected_mcp else None,
            "mcp_route_accuracy": (1.0 if has_mcp and intent_top1 == 1.0 else 0.0) if expected_mcp else None,
            "no_retrieval_accuracy": (1.0 if not has_kb and not has_mcp else 0.0) if system_case else None,
            "eval_latency_ms": record.get("latency_ms"),
            "mcp_context_nonempty": bool(record.get("mcp_context")) if expected_mcp else None,
        })

    overall = {
        "run_success_rate": mean([1.0 if sample["final_status"] == "success" else 0.0 for sample in samples]),
        "intent_top1": mean([sample["intent_top1"] for sample in samples]),
        "doc_hit_at_5": scoped_mean(samples, "hit_at_5", lambda sample: sample["requires_rag"]),
        "doc_recall_at_5": scoped_mean(samples, "recall_at_5", lambda sample: sample["requires_rag"]),
        "mrr_at_10": scoped_mean(samples, "mrr_at_10", lambda sample: sample["requires_rag"]),
        "kb_route_accuracy": scoped_mean(samples, "kb_route_accuracy", lambda sample: sample["requires_rag"]),
        "mcp_invocation_rate": scoped_mean(samples, "mcp_invocation_rate", lambda sample: sample["expected_mcp"]),
        "mcp_route_accuracy": scoped_mean(samples, "mcp_route_accuracy", lambda sample: sample["expected_mcp"]),
        "no_retrieval_accuracy": scoped_mean(samples, "no_retrieval_accuracy", lambda sample: sample["system_case"]),
        "eval_latency_ms_mean": mean([sample["eval_latency_ms"] for sample in samples]),
        "eval_latency_ms_p50": percentile([sample["eval_latency_ms"] for sample in samples], 0.50),
        "eval_latency_ms_p95": percentile([sample["eval_latency_ms"] for sample in samples], 0.95),
    }
    by_intent = defaultdict(list)
    for sample in samples:
        by_intent[sample["intent_l2"]].append(sample)
    metrics = ["intent_top1", "hit_at_5", "recall_at_5", "mrr_at_10", "kb_route_accuracy", "mcp_invocation_rate", "mcp_route_accuracy", "no_retrieval_accuracy", "eval_latency_ms"]
    grouped = {
        intent: {metric: mean([sample.get(metric) for sample in items]) for metric in metrics}
        for intent, items in sorted(by_intent.items())
    }
    segments = {
        "static_rag": {
            "sample_count": sum(sample["requires_rag"] for sample in samples),
            "intent_top1": scoped_mean(samples, "intent_top1", lambda sample: sample["requires_rag"]),
            "doc_hit_at_5": scoped_mean(samples, "hit_at_5", lambda sample: sample["requires_rag"]),
            "doc_recall_at_5": scoped_mean(samples, "recall_at_5", lambda sample: sample["requires_rag"]),
            "kb_route_accuracy": scoped_mean(samples, "kb_route_accuracy", lambda sample: sample["requires_rag"]),
            "eval_latency_ms_mean": scoped_mean(samples, "eval_latency_ms", lambda sample: sample["requires_rag"]),
        },
        "mcp": {
            "sample_count": sum(sample["expected_mcp"] for sample in samples),
            "intent_top1": scoped_mean(samples, "intent_top1", lambda sample: sample["expected_mcp"]),
            "mcp_invocation_rate": scoped_mean(samples, "mcp_invocation_rate", lambda sample: sample["expected_mcp"]),
            "mcp_route_accuracy": scoped_mean(samples, "mcp_route_accuracy", lambda sample: sample["expected_mcp"]),
            "eval_latency_ms_mean": scoped_mean(samples, "eval_latency_ms", lambda sample: sample["expected_mcp"]),
        },
        "system": {
            "sample_count": sum(sample["system_case"] for sample in samples),
            "intent_top1": scoped_mean(samples, "intent_top1", lambda sample: sample["system_case"]),
            "no_retrieval_accuracy": scoped_mean(samples, "no_retrieval_accuracy", lambda sample: sample["system_case"]),
            "eval_latency_ms_mean": scoped_mean(samples, "eval_latency_ms", lambda sample: sample["system_case"]),
        },
    }
    payload = {
        "run": str(Path(args.run)),
        "sample_count": len(samples),
        "overall": overall,
        "segments": segments,
        "by_intent_l2": grouped,
        "per_sample": samples,
        "ragas": {
            "status": "not_measured",
            "reason": "当前环境未安装 ragas，且旁路 /rag/eval 不返回 SSE 最终回答；不能将规则指标伪装为 faithfulness、answer_relevancy、answer_correctness、context_precision 或 context_recall。",
            "eligible_query_ids": [sample["query_id"] for sample in samples if sample["requires_rag"]],
        },
    }
    out = Path(args.out) if args.out else Path(args.run).with_name(Path(args.run).stem + "_qingju_scores.json")
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()
