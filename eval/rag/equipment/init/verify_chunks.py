from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "init"))

from client import RagentClient, STATE, load_json


def main() -> None:
    document_map = load_json(STATE / "doc_id_map.json")
    if not document_map:
        raise SystemExit("missing state/doc_id_map.json, run upload_docs.py first")
    client = RagentClient()
    client.login()
    counts = {}
    for document_code, metadata in document_map.items():
        response = client.get_json(f"/knowledge-base/docs/{metadata['ragent_doc_id']}/chunks") or {}
        chunks = response if isinstance(response, list) else response.get("records", [])
        counts[document_code] = len(chunks)
    invalid = {code: count for code, count in counts.items() if count < 2 or count > 3}
    distribution = {count: list(counts.values()).count(count) for count in sorted(set(counts.values()))}
    print(f"chunk distribution={distribution}")
    if invalid:
        raise SystemExit(f"documents outside 2-3 chunks: {invalid}")
    print(f"verified {len(counts)} equipment documents: each has 2-3 chunks")


if __name__ == "__main__":
    main()
