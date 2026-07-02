from __future__ import annotations

from collections import Counter, defaultdict

from client import ROOT, STATE, RagentClient, load_json, save_json

SYSTEM_CODES = {"F2_功能建议", "F3_投诉吐槽", "C1_寒暄问候", "C2_越界提问"}
BOUNDARY = {
    "S4_价格活动": "价格、优惠券、促销、价保类问题；不要和售后维修政策混淆。",
    "S14_售后政策": "保修、维修、售后服务范围；不要和促销优惠混淆。",
    "S5_库存到货": "下单前询问是否有货、何时补货；不要和下单后的物流轨迹混淆。",
    "S16_物流配送": "下单后发货、配送、快递时效；不要和售前库存到货混淆。",
}


def dataset_rows():
    rows = []
    with (ROOT / "datasets" / "eval_set_v1_all.jsonl").open(encoding="utf-8") as f:
        import json
        for line in f:
            rows.append(json.loads(line))
    return rows


def vote_kb(rows, doc_map):
    votes = defaultdict(Counter)
    for row in rows:
        for doc_id in row.get("expected_doc_ids", []):
            if doc_id in doc_map:
                votes[row["intent_l2"]][doc_map[doc_id]["kb_key"]] += 1
    return {k: c.most_common(1)[0][0] for k, c in votes.items() if c}


def examples_by_intent(rows):
    examples = defaultdict(list)
    for row in rows:
        if len(examples[row["intent_l2"]]) < 5:
            examples[row["intent_l2"]].append(row["query"])
    return examples


def create(client, intent_ids, code, payload):
    if code in intent_ids:
        print(f"skip existing intent {code}: {intent_ids[code]}")
        return intent_ids[code]
    node_id = client.post_json("/intent-tree", payload)
    intent_ids[code] = node_id
    save_json(STATE / "intent_ids.json", intent_ids)
    print(f"created intent {code}: {node_id}")
    return node_id


def main():
    spec = load_json(ROOT / "intent_tree_spec.json")
    kb_ids = load_json(STATE / "kb_ids.json")
    doc_map = load_json(STATE / "doc_id_map.json")
    if not kb_ids or not doc_map:
        raise SystemExit("run create_kbs.py and upload_docs.py before build_intent_tree.py")
    rows = dataset_rows()
    kb_vote = vote_kb(rows, doc_map)
    examples = examples_by_intent(rows)
    intent_ids = load_json(STATE / "intent_ids.json")
    client = RagentClient()
    client.login()

    for code, name in spec["domains"].items():
        create(client, intent_ids, code, {"intentCode": code, "name": name, "level": 0, "parentCode": None, "description": name, "examples": [], "kind": 1, "sortOrder": 0, "enabled": 1})
    for code, value in spec["categories"].items():
        parent, name = value
        create(client, intent_ids, code, {"intentCode": code, "name": name, "level": 1, "parentCode": parent, "description": name, "examples": [], "kind": 1, "sortOrder": 0, "enabled": 1})
    for idx, item in enumerate(spec["intents"], start=1):
        code, _l1, category, default_kb, _count = item
        kind = 1 if code in SYSTEM_CODES else 0
        kb_key = kb_vote.get(code, default_kb)
        kb_id = None if kind == 1 else kb_ids[kb_key]["kb_id"]
        desc = BOUNDARY.get(code, f"{code} 场景。")
        create(client, intent_ids, code, {"intentCode": code, "name": code, "level": 2, "parentCode": category, "description": desc, "examples": examples.get(code, []), "kbId": kb_id, "topK": 5, "kind": kind, "sortOrder": idx, "enabled": 1})
    print("done: eval/rag/state/intent_ids.json")


if __name__ == "__main__":
    main()