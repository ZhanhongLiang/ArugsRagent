from __future__ import annotations

import argparse
import time

from client import STATE, RagentClient, load_json

TEA_COLLECTION_PREFIX = "kb-tea-"
TEA_NAME_KEYWORDS = ("茶品", "茶")


def discover_kbs(client: RagentClient) -> dict:
    page = client.get_json("/knowledge-base?current=1&size=200")
    records = page.get("records", []) if isinstance(page, dict) else []
    result = {}
    for item in records:
        collection = item.get("collectionName") or item.get("collection_name") or ""
        name = item.get("name") or ""
        if collection.startswith(TEA_COLLECTION_PREFIX) or any(k in name for k in TEA_NAME_KEYWORDS):
            key = collection.replace(TEA_COLLECTION_PREFIX, "") if collection.startswith(TEA_COLLECTION_PREFIX) else item.get("id")
            result[key] = {
                "kb_id": item["id"],
                "name": name,
                "collection_name": collection,
                "embedding_model": item.get("embeddingModel"),
                "document_count": item.get("documentCount"),
            }
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--yes", action="store_true", help="actually delete KBs")
    parser.add_argument("--discover", action="store_true", help="discover tea KBs from backend even if state/kb_ids.json exists")
    args = parser.parse_args()

    kb_ids = load_json(STATE / "kb_ids.json")
    doc_map = load_json(STATE / "doc_id_map.json")
    client = None

    if args.discover or not kb_ids:
        client = RagentClient()
        client.login()
        discovered = discover_kbs(client)
        if discovered:
            kb_ids = discovered

    print(f"KBs to delete: {list(kb_ids.keys())}")
    for key, meta in kb_ids.items():
        print(f"  - {key}: {meta.get('name')} ({meta.get('collection_name')}) docs={meta.get('document_count')}")
    print(f"docs recorded locally: {len(doc_map)}")

    if not args.yes:
        print("dry-run only. add --yes to delete KBs and local state files")
        return
    if not kb_ids:
        print("no KBs found. Nothing to delete. If backend has old KBs, check RAGENT_BASE_URL/login or delete them in admin UI.")
        return

    for i in range(3, 0, -1):
        print(f"deleting in {i}...")
        time.sleep(1)
    if client is None:
        client = RagentClient()
        client.login()
    for key, meta in kb_ids.items():
        client.delete(f"/knowledge-base/{meta['kb_id']}")
        print(f"deleted KB {key}: {meta['kb_id']}")
    for name in ["kb_ids.json", "doc_id_map.json", "intent_ids.json"]:
        path = STATE / name
        if path.exists():
            path.unlink()
            print(f"removed {path}")
    print("intent tree rows may still need manual cleanup in admin console / t_intent_node")


if __name__ == "__main__":
    main()