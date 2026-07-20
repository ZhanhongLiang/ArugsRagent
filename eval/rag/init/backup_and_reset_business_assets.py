from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

from client import RagentClient, save_json


def page_records(client: RagentClient, path: str) -> list[dict]:
    records: list[dict] = []
    current = 1
    while True:
        separator = "&" if "?" in path else "?"
        page = client.get_json(f"{path}{separator}current={current}&size=100") or {}
        if isinstance(page, list):
            page_records_value = page
        else:
            page_records_value = page.get("records") or []
        records.extend(page_records_value)
        if len(page_records_value) < 100:
            return records
        current += 1


def flatten_intent_nodes(nodes: list[dict]) -> list[dict]:
    result: list[dict] = []
    for node in nodes:
        result.append(node)
        result.extend(flatten_intent_nodes(node.get("children") or []))
    return result


def backup_scopes(client: RagentClient, knowledge_bases: list[dict], documents: list[dict]) -> dict:
    scopes: dict[str, object] = {"knowledge_bases": {}, "documents": {}}
    for knowledge_base in knowledge_bases:
        kb_id = str(knowledge_base["id"])
        scopes["knowledge_bases"][kb_id] = client.get_json(f"/knowledge-access/KNOWLEDGE_BASE/{kb_id}/scopes")
    for document in documents:
        document_id = str(document["id"])
        scopes["documents"][document_id] = client.get_json(f"/knowledge-access/DOCUMENT/{document_id}/scopes")
    return scopes


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Back up and remove all active knowledge-base business assets and intent nodes through existing APIs."
    )
    parser.add_argument("--apply", action="store_true", help="Perform deletion after writing a backup. Omit for backup-only dry run.")
    parser.add_argument("--backup-dir", type=Path, default=None)
    args = parser.parse_args()

    client = RagentClient()
    client.login()

    knowledge_bases = page_records(client, "/knowledge-base")
    documents: list[dict] = []
    for knowledge_base in knowledge_bases:
        kb_id = str(knowledge_base["id"])
        documents.extend(page_records(client, f"/knowledge-base/{kb_id}/docs"))
    trees = client.get_json("/intent-tree/trees") or []
    intents = flatten_intent_nodes(trees)
    scopes = backup_scopes(client, knowledge_bases, documents)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = args.backup_dir or Path("eval/rag/equipment/backups") / f"before_equipment_reset_{timestamp}"
    save_json(backup_dir / "knowledge_bases.json", knowledge_bases)
    save_json(backup_dir / "documents.json", documents)
    save_json(backup_dir / "intent_trees.json", trees)
    save_json(backup_dir / "resource_scopes.json", scopes)
    print(f"backup written to {backup_dir}")
    print(f"planned deletion: {len(documents)} documents, {len(knowledge_bases)} knowledge bases, {len(intents)} intent nodes")

    if not args.apply:
        print("backup-only mode: pass --apply to delete the backed-up business assets")
        return

    for document in documents:
        document_id = str(document["id"])
        client.delete(f"/knowledge-base/docs/{document_id}")
        print(f"deleted document {document_id}")
    for knowledge_base in knowledge_bases:
        kb_id = str(knowledge_base["id"])
        client.delete(f"/knowledge-base/{kb_id}")
        print(f"deleted knowledge base {kb_id}")
    for node in sorted(intents, key=lambda item: item.get("level") or 0, reverse=True):
        node_id = str(node["id"])
        client.delete(f"/intent-tree/{node_id}")
        print(f"deleted intent {node.get('intentCode')} ({node_id})")

    remaining_kbs = page_records(client, "/knowledge-base")
    remaining_intents = flatten_intent_nodes(client.get_json("/intent-tree/trees") or [])
    if remaining_kbs or remaining_intents:
        raise SystemExit(
            f"cleanup incomplete: remaining knowledge bases={len(remaining_kbs)}, intents={len(remaining_intents)}"
        )
    print("cleanup complete: no active knowledge bases or intent nodes remain")


if __name__ == "__main__":
    main()
