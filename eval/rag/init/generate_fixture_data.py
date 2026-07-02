from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "knowledge_base"
DATASET_ROOT = ROOT / "datasets"

KB_SPECS = {
    "product": {"name": "比特严选 商品库", "collection_name": "kb-product", "embedding_model": "qwen-emb-8b"},
    "manual": {"name": "比特严选 使用手册库", "collection_name": "kb-manual", "embedding_model": "qwen-emb-8b"},
    "policy": {"name": "比特严选 政策库", "collection_name": "kb-policy", "embedding_model": "qwen-emb-8b"},
    "faq": {"name": "比特严选 FAQ库", "collection_name": "kb-faq", "embedding_model": "qwen-emb-8b"},
}
DOMAINS = {"SUPPORT": "客服咨询", "FEEDBACK": "反馈投诉", "CHAT": "闲聊兜底"}
CATEGORIES = {"SUPPORT_PRESALE": ["SUPPORT", "售前咨询"], "SUPPORT_USAGE": ["SUPPORT", "使用指导"], "SUPPORT_AFTERSALES": ["SUPPORT", "售后服务"], "FEEDBACK_ALL": ["FEEDBACK", "反馈处理"], "CHAT_ALL": ["CHAT", "闲聊与边界"]}
INTENTS = [["S1_选购推荐", "SUPPORT", "SUPPORT_PRESALE", "product", 9], ["S2_参数咨询", "SUPPORT", "SUPPORT_PRESALE", "product", 11], ["S3_对比选购", "SUPPORT", "SUPPORT_PRESALE", "product", 9], ["S4_价格活动", "SUPPORT", "SUPPORT_PRESALE", "policy", 7], ["S5_库存到货", "SUPPORT", "SUPPORT_PRESALE", "product", 6], ["S6_配件兼容", "SUPPORT", "SUPPORT_PRESALE", "product", 7], ["S7_适用场景", "SUPPORT", "SUPPORT_PRESALE", "product", 7], ["S8_操作指引", "SUPPORT", "SUPPORT_USAGE", "manual", 8], ["S9_配网连接", "SUPPORT", "SUPPORT_USAGE", "manual", 7], ["S10_APP功能", "SUPPORT", "SUPPORT_USAGE", "manual", 7], ["S11_固件升级", "SUPPORT", "SUPPORT_USAGE", "manual", 5], ["S12_生态联动", "SUPPORT", "SUPPORT_USAGE", "manual", 6], ["S13_保养维护", "SUPPORT", "SUPPORT_USAGE", "manual", 9], ["S14_售后政策", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 8], ["S15_退换货", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 7], ["S16_物流配送", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 6], ["S17_发票会员", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 6], ["F1_故障报告", "FEEDBACK", "FEEDBACK_ALL", "faq", 5], ["F2_功能建议", "FEEDBACK", "FEEDBACK_ALL", "system", 5], ["F3_投诉吐槽", "FEEDBACK", "FEEDBACK_ALL", "system", 5], ["C1_寒暄问候", "CHAT", "CHAT_ALL", "system", 5], ["C2_越界提问", "CHAT", "CHAT_ALL", "system", 5]]
DOC_PLANS = [["01_product/detail", "PROD_PHONE", 15, "product"], ["01_product/detail", "PROD_VAC", 10, "product"], ["01_product/detail", "PROD_AIR", 8, "product"], ["01_product/detail", "PROD_SPK", 6, "product"], ["01_product/detail", "PROD_HEALTH", 4, "product"], ["01_product/detail", "PROD_ACCESSORY", 2, "product"], ["01_product/guide", "GUIDE_PHONE", 8, "product"], ["01_product/guide", "GUIDE_VAC", 4, "product"], ["01_product/guide", "GUIDE_GIFT", 2, "product"], ["01_product/guide", "GUIDE_HOME", 2, "product"], ["01_product/guide", "GUIDE_AFTERSALE", 4, "product"], ["02_manual/app", "APP_GUIDE", 5, "manual"], ["02_manual/network", "NET_GUIDE", 5, "manual"], ["02_manual/product", "MANUAL_VAC", 5, "manual"], ["02_manual/product", "MANUAL_PHONE", 3, "manual"], ["02_manual/product", "MANUAL_AIR", 2, "manual"], ["02_manual/product", "MANUAL_SPK", 2, "manual"], ["02_manual/product", "MANUAL_HEALTH", 1, "manual"], ["02_manual/product", "AUTO_GUIDE", 2, "manual"], ["03_policy/warranty", "POLICY_WARRANTY", 5, "policy"], ["03_policy/return", "POLICY_RETURN", 4, "policy"], ["03_policy/logistics", "POLICY_LOGISTICS", 2, "policy"], ["03_policy/invoice_vip", "POLICY_INVOICE", 1, "policy"], ["03_policy/invoice_vip", "POLICY_VIP", 1, "policy"], ["03_policy/invoice_vip", "POLICY_PRICE", 1, "policy"], ["03_policy/invoice_vip", "POLICY_COUPON", 1, "policy"], ["04_faq/error_code", "FAQ_ERR", 6, "faq"], ["04_faq/trouble", "FAQ_VAC", 2, "faq"], ["04_faq/trouble", "FAQ_AIR", 1, "faq"], ["04_faq/trouble", "FAQ_RET", 1, "faq"]]
POOLS = {"S1_选购推荐": ["GUIDE_PHONE_002", "GUIDE_PHONE_003", "PROD_PHONE_006", "PROD_PHONE_003"], "S2_参数咨询": ["PROD_PHONE_001", "PROD_PHONE_006"], "S3_对比选购": ["GUIDE_PHONE_004", "PROD_PHONE_001", "PROD_PHONE_006"], "S4_价格活动": ["POLICY_PRICE_001", "POLICY_COUPON_001"], "S5_库存到货": ["PROD_PHONE_003", "PROD_VAC_003"], "S6_配件兼容": ["PROD_ACCESSORY_001", "PROD_ACCESSORY_002", "MANUAL_PHONE_002"], "S7_适用场景": ["GUIDE_GIFT_001", "GUIDE_HOME_001"], "S8_操作指引": ["MANUAL_VAC_001"], "S9_配网连接": ["NET_GUIDE_001", "NET_GUIDE_002"], "S10_APP功能": ["APP_GUIDE_001", "APP_GUIDE_002"], "S11_固件升级": ["APP_GUIDE_003", "MANUAL_PHONE_003"], "S12_生态联动": ["AUTO_GUIDE_002", "MANUAL_SPK_001", "MANUAL_VAC_001"], "S13_保养维护": ["MANUAL_VAC_003", "MANUAL_AIR_001"], "S14_售后政策": ["POLICY_WARRANTY_001", "GUIDE_AFTERSALE_004"], "S15_退换货": ["POLICY_RETURN_002", "FAQ_RET_001", "GUIDE_AFTERSALE_004"], "S16_物流配送": ["POLICY_LOGISTICS_001", "POLICY_LOGISTICS_002"], "S17_发票会员": ["POLICY_INVOICE_001", "POLICY_VIP_001"], "F1_故障报告": ["FAQ_VAC_001", "FAQ_ERR_001", "FAQ_AIR_001"]}
QUERIES = {"S1_选购推荐": "预算 {n}000 元左右，想买一款拍照还不错的手机，推荐哪款？", "S2_参数咨询": "Redmi K70 的屏幕、电池和快充参数分别是多少？", "S3_对比选购": "Redmi K70 和小米 14 主要差别是什么？", "S4_价格活动": "现在下单有没有优惠券，价保规则是怎样的？", "S5_库存到货": "这款扫地机器人现在没货，大概什么时候能到？", "S6_配件兼容": "K70 能不能用 120W 充电器，旧充电头兼容吗？", "S7_适用场景": "送父母实用一点的智能设备，应该选什么？", "S8_操作指引": "扫地机首次使用，如何开机？", "S9_配网连接": "扫地机连不上 Wi-Fi，应该怎么重新配网？", "S10_APP功能": "米家 APP 里怎么设置定时清扫和房间名称？", "S11_固件升级": "设备提示有新固件，升级前需要注意什么？", "S12_生态联动": "智能音箱能控制扫地机吗？", "S13_保养维护": "扫地机器人多久清理一次滤网和主刷？", "S14_售后政策": "商品坏了怎么保修，保修期从什么时候开始算？", "S15_退换货": "收到后不想要了还能退吗，流程怎么走？", "S16_物流配送": "买了三天还没发货，物流配送一般多久？", "S17_发票会员": "怎么开发票，会员积分可以抵扣吗？", "F1_故障报告": "扫地机用了三个月突然报错，怎么排查？", "F2_功能建议": "我希望 APP 增加耗材到期提醒功能，可以反馈吗？", "F3_投诉吐槽": "客服半天不回，体验太差了，我要投诉。", "C1_寒暄问候": "你好，在吗？", "C2_越界提问": "今天杭州天气怎么样，适合出门吗？"}


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def make_docs():
    catalog = {}
    for rel, prefix, count, kb in DOC_PLANS:
        folder = KB_ROOT / rel
        folder.mkdir(parents=True, exist_ok=True)
        for i in range(1, count + 1):
            doc_id = f"{prefix}_{i:03d}"
            path = folder / f"{doc_id}.md"
            body = f"""# {doc_id} 比特严选资料

## 文档定位
本文档属于 `{kb}` 知识库，业务文档 ID 为 `{doc_id}`。评测集中的 expected_doc_ids 使用该业务码，不使用数据库雪花 ID。

## 核心内容
比特严选覆盖手机、扫地机器人、空气净化器、智能音箱、健康设备、配件、售后政策、退换货、物流、发票会员和故障排查。

## 回答规则
当用户问题命中本资料相关主题时，应基于资料回答事实、参数、流程和边界。信息不足时说明当前资料未覆盖，不要编造承诺。

## 可检索证据
`{doc_id}` 可作为 RAG 检索命中的标准文档。不同评测样本会通过 expected_doc_ids 或 expected_doc_ids_nice 引用本文档。
"""
            path.write_text(body, encoding="utf-8")
            catalog[doc_id] = {"kb_key": kb, "rel_path": str(path.relative_to(ROOT)).replace("\\", "/")}
    assert len(catalog) == 115, len(catalog)
    return catalog


def sample(intent_code, l1, kb, idx):
    requires = kb != "system" or (intent_code == "F3_投诉吐槽" and idx <= 2)
    docs = POOLS.get(intent_code, [])
    must = docs[:2] if requires else []
    nice = docs[2:] if requires and intent_code in {"S1_选购推荐", "S3_对比选购", "S6_配件兼容", "S12_生态联动", "S15_退换货", "F1_故障报告"} else []
    prefix = intent_code.split("_")[0]
    query = QUERIES[intent_code].format(n=(idx % 4) + 2)
    return {"query_id": f"{prefix}-{idx:02d}", "query": query, "intent_l1": l1, "intent_l2": intent_code, "difficulty": ["easy", "medium", "hard"][(idx - 1) % 3], "requires_rag": requires, "expected_answer_type": "no_rag" if not requires else "knowledge_answer", "expected_doc_ids": must, "expected_doc_ids_nice": nice, "trap_type": "course_fixture", "ground_truth": f"该问题应按 {intent_code} 处理。" + (f"核心证据来自 {', '.join(must)}。" if must else "应走系统兜底或人工反馈，不应强行检索知识库。"), "eval_metrics": ["intent_accuracy", "hit@5", "recall@5", "mrr@10"] if requires else ["intent_accuracy", "no_rag"]}


def make_datasets():
    rows = []
    for code, l1, _cat, kb, count in INTENTS:
        for i in range(1, count + 1):
            rows.append(sample(code, l1, kb, i))
    assert len(rows) == 150, len(rows)
    main = []
    covered = set()
    for row in rows:
        if row["intent_l2"] in {"S7_适用场景", "C1_寒暄问候", "C2_越界提问"}:
            continue
        if row["intent_l2"] not in covered:
            main.append(row)
            covered.add(row["intent_l2"])
        if len(main) == 19:
            break
    main.append(next(r for r in rows if r["query_id"] == "S1-02"))
    DATASET_ROOT.mkdir(parents=True, exist_ok=True)
    for path, data in [(DATASET_ROOT / "eval_set_v1_all.jsonl", rows), (DATASET_ROOT / "eval_set_v1.jsonl", main)]:
        with path.open("w", encoding="utf-8", newline="\n") as f:
            for item in data:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")


def main():
    catalog = make_docs()
    make_datasets()
    write_json(ROOT / "kb_specs.json", KB_SPECS)
    write_json(ROOT / "doc_catalog.json", catalog)
    write_json(ROOT / "intent_tree_spec.json", {"domains": DOMAINS, "categories": CATEGORIES, "intents": INTENTS})
    print("generated course fixture: 4 KB specs, 115 docs, 20 main samples, 150 full samples")


if __name__ == "__main__":
    main()