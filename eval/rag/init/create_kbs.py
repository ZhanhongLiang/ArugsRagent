from __future__ import annotations

from client import ROOT, STATE, RagentClient, load_json, save_json


def main():
    specs = load_json(ROOT / "kb_specs.json")
    kb_ids = load_json(STATE / "kb_ids.json")
    client = RagentClient()
    client.login()
    for key, spec in specs.items():
        if key in kb_ids:
            print(f"skip existing KB {key}: {kb_ids[key]['kb_id']}")
            continue
        kb_id = client.post_json("/knowledge-base", {
            "name": spec["name"],
            "embeddingModel": spec["embedding_model"],
            "collectionName": spec["collection_name"],
        })
        kb_ids[key] = {"kb_id": kb_id, **spec}
        save_json(STATE / "kb_ids.json", kb_ids)
        print(f"created KB {key}: {kb_id}")
    print("done: eval/rag/state/kb_ids.json")


if __name__ == "__main__":
    main()