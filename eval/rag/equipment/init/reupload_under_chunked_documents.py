from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "init"))

from client import RagentClient, STATE, load_json, save_json


CHUNK_CONFIG = '{"targetChars":1400,"maxChars":1800,"minChars":600,"overlapChars":0}'


def chunk_count(client: RagentClient, document_id: str) -> int:
    response = client.get_json(f"/knowledge-base/docs/{document_id}/chunks") or {}
    return len(response if isinstance(response, list) else response.get("records", []))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Re-upload only equipment documents that have too few completed chunks."
    )
    parser.add_argument("--max-existing-chunks", type=int, default=1)
    parser.add_argument("--sleep", type=float, default=0.2)
    args = parser.parse_args()

    catalog = load_json(ROOT / "doc_catalog.json")
    document_map = load_json(STATE / "doc_id_map.json")
    if not document_map:
        raise SystemExit("missing state/doc_id_map.json, run upload_docs.py first")

    client = RagentClient()
    client.login()
    candidates = [
        document_code
        for document_code, metadata in document_map.items()
        if chunk_count(client, metadata["ragent_doc_id"]) <= args.max_existing_chunks
    ]
    print(f"re-upload candidates={len(candidates)}")
    for document_code in candidates:
        metadata = document_map[document_code]
        source = catalog[document_code]
        file_path = ROOT / source["rel_path"]
        client.delete(f"/knowledge-base/docs/{metadata['ragent_doc_id']}")
        uploaded = client.upload_file(
            f"/knowledge-base/{metadata['kb_id']}/docs/upload",
            file_path,
            {
                "sourceType": "file",
                "scheduleEnabled": "false",
                "processMode": "chunk",
                "chunkStrategy": "structure_aware",
                "chunkConfig": CHUNK_CONFIG,
            },
        )
        document_id = uploaded["id"] if isinstance(uploaded, dict) else uploaded
        client.post_json(f"/knowledge-base/docs/{document_id}/chunk", {})
        document_map[document_code] = {**metadata, "ragent_doc_id": document_id}
        save_json(STATE / "doc_id_map.json", document_map)
        print(f"re-uploaded {document_code} -> {document_id}")
        if args.sleep:
            time.sleep(args.sleep)
    print("re-upload complete")


if __name__ == "__main__":
    main()
