from __future__ import annotations

import argparse
import time
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "init"))
from client import STATE, RagentClient, load_json


def chunk_count(client: RagentClient, document_id: str) -> int:
    page = client.get_json(f"/knowledge-base/docs/{document_id}/chunks")
    if isinstance(page, list):
        return len(page)
    if isinstance(page, dict):
        return len(page.get("records", []))
    return 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--min-chunks", type=int, default=2)
    parser.add_argument("--max-chunks", type=int, default=3)
    parser.add_argument("--wait-seconds", type=int, default=60)
    args = parser.parse_args()

    doc_map = load_json(STATE / "doc_id_map.json")
    if not doc_map:
        raise SystemExit("missing state/doc_id_map.json, run upload_docs.py first")

    client = RagentClient()
    client.login()
    deadline = time.monotonic() + args.wait_seconds
    counts = {}
    while True:
        counts = {doc_code: chunk_count(client, meta["ragent_doc_id"]) for doc_code, meta in doc_map.items()}
        pending = [doc_code for doc_code, count in counts.items() if count == 0]
        if not pending or time.monotonic() >= deadline:
            break
        time.sleep(3)

    outside_range = {doc_code: count for doc_code, count in counts.items() if not args.min_chunks <= count <= args.max_chunks}
    distribution = {}
    for count in counts.values():
        distribution[count] = distribution.get(count, 0) + 1
    print(f"chunk distribution={dict(sorted(distribution.items()))}")
    if outside_range:
        print(f"outside {args.min_chunks}-{args.max_chunks} chunks: {outside_range}")
        raise SystemExit(1)
    print(f"verified {len(counts)} documents: each has {args.min_chunks}-{args.max_chunks} chunks")


if __name__ == "__main__":
    main()
