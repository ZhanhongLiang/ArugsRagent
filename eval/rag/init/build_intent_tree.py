from __future__ import annotations

import argparse

from client import ROOT, STATE, RagentClient, load_json, save_json


def create(client: RagentClient, intent_ids: dict, code: str, payload: dict, dry_run: bool) -> str | None:
    if code in intent_ids:
        print(f"skip existing intent {code}: {intent_ids[code]}")
        return intent_ids[code]
    if dry_run:
        print(f"would create intent {code}: kind={payload['kind']} parent={payload['parentCode']}")
        return None
    node_id = client.post_json("/intent-tree", payload)
    intent_ids[code] = node_id
    save_json(STATE / "intent_ids.json", intent_ids)
    print(f"created intent {code}: {node_id}")
    return node_id


def normalize_leaf(item: dict | list) -> dict:
    if isinstance(item, dict):
        return {
            "code": item["code"],
            "name": item.get("name", item["code"]),
            "parent": item["parent"],
            "description": item.get("description", item["code"]),
            "examples": item.get("examples", []),
            "kind": item.get("kind", 0),
            "kb_key": item.get("kb_key"),
            "mcp_tool_id": item.get("mcp_tool_id"),
            "top_k": item.get("top_k", 5),
        }

    code, _domain, parent, kb_key, _count = item
    return {
        "code": code,
        "name": code,
        "parent": parent,
        "description": code,
        "examples": [],
        "kind": 1 if kb_key == "system" else 0,
        "kb_key": None if kb_key == "system" else kb_key,
        "mcp_tool_id": None,
        "top_k": 5,
    }


def normalize_domain(code: str, value: str | dict) -> dict:
    if isinstance(value, str):
        return {"code": code, "name": value, "kind": 1}
    return {
        "code": code,
        "name": value.get("name", code),
        "kind": value.get("kind", 1),
        "description": value.get("description", value.get("name", code)),
    }


def normalize_category(code: str, value: list | dict) -> dict:
    if isinstance(value, list):
        parent, name = value
        return {"code": code, "parent": parent, "name": name, "kind": 1}
    return {
        "code": code,
        "parent": value["parent"],
        "name": value.get("name", code),
        "kind": value.get("kind", 1),
        "description": value.get("description", value.get("name", code)),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    spec = load_json(ROOT / "intent_tree_spec.json")
    kb_ids = load_json(STATE / "kb_ids.json")
    if not kb_ids and not args.dry_run:
        raise SystemExit("missing state/kb_ids.json, run create_kbs.py before building the intent tree")

    intent_ids = load_json(STATE / "intent_ids.json")
    client = RagentClient()
    if not args.dry_run:
        client.login()

    for code, value in spec["domains"].items():
        item = normalize_domain(code, value)
        create(client, intent_ids, code, {
            "intentCode": item["code"],
            "name": item["name"],
            "level": 0,
            "parentCode": None,
            "description": item.get("description", item["name"]),
            "examples": [],
            "kind": item["kind"],
            "sortOrder": 0,
            "enabled": 1,
        }, args.dry_run)

    for code, value in spec["categories"].items():
        item = normalize_category(code, value)
        create(client, intent_ids, code, {
            "intentCode": item["code"],
            "name": item["name"],
            "level": 1,
            "parentCode": item["parent"],
            "description": item.get("description", item["name"]),
            "examples": [],
            "kind": item["kind"],
            "sortOrder": 0,
            "enabled": 1,
        }, args.dry_run)

    for sort_order, raw_item in enumerate(spec["intents"], start=1):
        item = normalize_leaf(raw_item)
        kb_id = None
        if item["kind"] == 0:
            kb_key = item["kb_key"]
            if not kb_key:
                raise SystemExit(f"missing knowledge base mapping for intent {item['code']}: {kb_key}")
            if kb_key not in kb_ids:
                if not args.dry_run:
                    raise SystemExit(f"missing knowledge base mapping for intent {item['code']}: {kb_key}")
            else:
                kb_id = kb_ids[kb_key]["kb_id"]
        create(client, intent_ids, item["code"], {
            "intentCode": item["code"],
            "name": item["name"],
            "level": 2,
            "parentCode": item["parent"],
            "description": item["description"],
            "examples": item["examples"],
            "kbId": kb_id,
            "mcpToolId": item["mcp_tool_id"],
            "topK": item["top_k"],
            "kind": item["kind"],
            "sortOrder": sort_order,
            "enabled": 1,
        }, args.dry_run)
    print(f"done: {STATE / 'intent_ids.json'}")


if __name__ == "__main__":
    main()
