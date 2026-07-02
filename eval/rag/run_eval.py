from __future__ import annotations

import argparse
import json
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

from init.client import ROOT, RagentClient


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="eval/rag/datasets/eval_set_v1.jsonl")
    parser.add_argument("--out", default=None)
    parser.add_argument("--sleep", type=float, default=0)
    args = parser.parse_args()
    dataset = Path(args.dataset)
    run_path = Path(args.out) if args.out else ROOT / "runs" / f"v1_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jsonl"
    run_path.parent.mkdir(parents=True, exist_ok=True)
    client = RagentClient()
    client.login()
    with run_path.open("w", encoding="utf-8", newline="\n") as out:
        for row in read_jsonl(dataset):
            start = time.time()
            result = {**row, "final_status": "success", "error": None}
            try:
                q = urllib.parse.quote(row["query"])
                eval_data = client.get_json(f"/rag/eval?question={q}")
                result.update({
                    "retrieved_doc_ids": eval_data.get("retrievedDocIds") or [],
                    "retrieved_chunk_ids": eval_data.get("retrievedChunkIds") or [],
                    "retrieved_contexts": eval_data.get("retrievedContexts") or [],
                    "retrieved_context_doc_ids": eval_data.get("retrievedContextDocIds") or [],
                    "intent_pred": (eval_data.get("intentLeafIds") or [None])[0],
                    "intent_pred_all": eval_data.get("intentLeafIds") or [],
                    "has_kb": eval_data.get("hasKb"),
                    "has_mcp": eval_data.get("hasMcp"),
                    "latency_ms": eval_data.get("latencyMs"),
                })
            except Exception as e:
                result["final_status"] = "error"
                result["error"] = str(e)
            result["runner_elapsed_ms"] = int((time.time() - start) * 1000)
            out.write(json.dumps(result, ensure_ascii=False) + "\n")
            print(f"{row['query_id']} {result['final_status']}")
            if args.sleep:
                time.sleep(args.sleep)
    print(run_path)


if __name__ == "__main__":
    main()