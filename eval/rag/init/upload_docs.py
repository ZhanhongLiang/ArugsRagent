from __future__ import annotations

import argparse
import time
from pathlib import Path

from client import ROOT, STATE, RagentClient, load_json, save_json

CHUNK_CONFIG = '{"targetChars":1400,"maxChars":1800,"minChars":600,"overlapChars":0}'


def iter_docs(catalog):
    for doc_id, meta in sorted(catalog.items(), key=lambda x: x[1]["rel_path"]):
        yield doc_id, meta, ROOT / meta["rel_path"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--sleep", type=float, default=0)
    args = parser.parse_args()

    catalog = load_json(ROOT / "doc_catalog.json")
    if args.dry_run:
        counts = {}
        for _, meta, _ in iter_docs(catalog):
            counts[meta["kb_key"]] = counts.get(meta["kb_key"], 0) + 1
        print(f"docs={len(catalog)} distribution={counts}")
        return

    kb_ids = load_json(STATE / "kb_ids.json")
    if not kb_ids:
        raise SystemExit("missing eval/rag/state/kb_ids.json, run create_kbs.py first")
    doc_map = load_json(STATE / "doc_id_map.json")

    client = RagentClient()
    client.login()
    for doc_id, meta, path in iter_docs(catalog):
        if doc_id in doc_map:
            print(f"skip uploaded {doc_id}")
            continue
        kb_key = meta["kb_key"]
        kb_id = kb_ids[kb_key]["kb_id"]
        data = client.upload_file(f"/knowledge-base/{kb_id}/docs/upload", path, {
            "sourceType": "file",
            "scheduleEnabled": "false",
            "processMode": "chunk",
            "chunkStrategy": "structure_aware",
            "chunkConfig": CHUNK_CONFIG,
        })
        ragent_doc_id = data["id"] if isinstance(data, dict) else data
        client.post_json(f"/knowledge-base/docs/{ragent_doc_id}/chunk", {})
        doc_map[doc_id] = {"ragent_doc_id": ragent_doc_id, "kb_key": kb_key, "kb_id": kb_id, "rel_path": meta["rel_path"]}
        save_json(STATE / "doc_id_map.json", doc_map)
        print(f"uploaded {doc_id} -> {ragent_doc_id}")
        if args.sleep:
            time.sleep(args.sleep)
    print("done: eval/rag/state/doc_id_map.json")


if __name__ == "__main__":
    main()