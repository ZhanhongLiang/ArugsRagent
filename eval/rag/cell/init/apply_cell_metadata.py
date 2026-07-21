from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "init"))

from client import RagentClient, load_json

STATE = ROOT / "state"


def wait_until_finished(client: RagentClient, document_id: str, timeout_seconds: int) -> str:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        document = client.get_json(f"/knowledge-base/docs/{document_id}")
        status = document.get("status", "")
        if status in {"success", "failed"}:
            return status
        time.sleep(1)
    raise TimeoutError(f"document chunking timed out: {document_id}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill cell-factory document metadata and rebuild vectors.")
    parser.add_argument("--sleep", type=float, default=0.0)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    catalog = load_json(ROOT / "doc_catalog.json")
    document_map = load_json(STATE / "doc_id_map.json")
    if not document_map:
        raise SystemExit("missing state/doc_id_map.json, run upload_docs.py first")
    if args.dry_run:
        print(f"would backfill {len(document_map)} cell-factory documents")
        return

    client = RagentClient()
    client.login()
    completed = 0
    for document_code, stored in sorted(document_map.items()):
        metadata = catalog[document_code]["metadata"]
        document_id = stored["ragent_doc_id"]
        client.put_json(f"/knowledge-base/docs/{document_id}", {
            "metadataJson": json.dumps(metadata, ensure_ascii=False),
        })
        client.post_json(f"/knowledge-base/docs/{document_id}/chunk", {})
        status = wait_until_finished(client, document_id, args.timeout)
        if status != "success":
            raise RuntimeError(f"metadata backfill chunking failed: {document_code} ({document_id})")
        completed += 1
        print(f"backfilled {document_code} -> {document_id}")
        if args.sleep:
            time.sleep(args.sleep)
    print(f"completed metadata backfill for {completed} documents")


if __name__ == "__main__":
    main()
