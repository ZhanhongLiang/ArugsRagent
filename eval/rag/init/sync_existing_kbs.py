from __future__ import annotations

from client import STATE, RagentClient, save_json

PREFIX = "kb-tea-"


def main():
    client = RagentClient()
    client.login()
    page = client.get_json("/knowledge-base?current=1&size=200")
    records = page.get("records", []) if isinstance(page, dict) else []
    kb_ids = {}
    for item in records:
        collection = item.get("collectionName") or ""
        if not collection.startswith(PREFIX):
            continue
        key = collection.removeprefix(PREFIX)
        kb_ids[key] = {
            "kb_id": item["id"],
            "name": item.get("name"),
            "collection_name": collection,
            "embedding_model": item.get("embeddingModel"),
        }
    if not kb_ids:
        raise SystemExit("no kb-tea-* knowledge bases found from backend")
    expected = {"product", "manual", "policy", "faq"}
    missing = expected - set(kb_ids)
    if missing:
        raise SystemExit(f"missing tea KBs: {sorted(missing)}; found={sorted(kb_ids)}")
    save_json(STATE / "kb_ids.json", kb_ids)
    print(f"synced {len(kb_ids)} KBs to eval/rag/state/kb_ids.json")
    for key, meta in sorted(kb_ids.items()):
        print(f"{key}: {meta['kb_id']} {meta['collection_name']}")


if __name__ == "__main__":
    main()