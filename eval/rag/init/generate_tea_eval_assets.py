from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "knowledge_base"
DATASET_ROOT = ROOT / "datasets"

KB_SPECS = {
    "product": {"name": "茶品严选 商品库", "collection_name": "kb-tea-product", "embedding_model": "qwen-emb-8b"},
    "manual": {"name": "茶品严选 使用手册库", "collection_name": "kb-tea-manual", "embedding_model": "qwen-emb-8b"},
    "policy": {"name": "茶品严选 政策库", "collection_name": "kb-tea-policy", "embedding_model": "qwen-emb-8b"},
    "faq": {"name": "茶品严选 FAQ库", "collection_name": "kb-tea-faq", "embedding_model": "qwen-emb-8b"},
}

DOMAINS = {"SUPPORT": "茶品客服咨询", "FEEDBACK": "反馈投诉", "CHAT": "闲聊兜底"}
CATEGORIES = {
    "SUPPORT_PRESALE": ["SUPPORT", "售前选购"],
    "SUPPORT_USAGE": ["SUPPORT", "冲泡与使用"],
    "SUPPORT_AFTERSALES": ["SUPPORT", "售后服务"],
    "FEEDBACK_ALL": ["FEEDBACK", "反馈处理"],
    "CHAT_ALL": ["CHAT", "闲聊与边界"],
}
INTENTS = [
    ["S1_选购推荐", "SUPPORT", "SUPPORT_PRESALE", "product", 9],
    ["S2_参数咨询", "SUPPORT", "SUPPORT_PRESALE", "product", 11],
    ["S3_对比选购", "SUPPORT", "SUPPORT_PRESALE", "product", 9],
    ["S4_价格活动", "SUPPORT", "SUPPORT_PRESALE", "policy", 7],
    ["S5_库存到货", "SUPPORT", "SUPPORT_PRESALE", "product", 6],
    ["S6_茶具兼容", "SUPPORT", "SUPPORT_PRESALE", "manual", 7],
    ["S7_适用场景", "SUPPORT", "SUPPORT_PRESALE", "product", 7],
    ["S8_冲泡指引", "SUPPORT", "SUPPORT_USAGE", "product", 8],
    ["S9_设备配网", "SUPPORT", "SUPPORT_USAGE", "manual", 7],
    ["S10_APP功能", "SUPPORT", "SUPPORT_USAGE", "manual", 7],
    ["S11_固件升级", "SUPPORT", "SUPPORT_USAGE", "manual", 5],
    ["S12_设备联动", "SUPPORT", "SUPPORT_USAGE", "manual", 6],
    ["S13_保养维护", "SUPPORT", "SUPPORT_USAGE", "manual", 9],
    ["S14_售后政策", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 8],
    ["S15_退换货", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 7],
    ["S16_物流配送", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 6],
    ["S17_发票会员", "SUPPORT", "SUPPORT_AFTERSALES", "policy", 6],
    ["F1_故障报告", "FEEDBACK", "FEEDBACK_ALL", "faq", 5],
    ["F2_功能建议", "FEEDBACK", "FEEDBACK_ALL", "system", 5],
    ["F3_投诉吐槽", "FEEDBACK", "FEEDBACK_ALL", "system", 5],
    ["C1_寒暄问候", "CHAT", "CHAT_ALL", "system", 5],
    ["C2_越界提问", "CHAT", "CHAT_ALL", "system", 5],
]

QUERY_TEMPLATES = {
    "S1_选购推荐": "预算 {n}00 元左右，想买一款适合日常喝的茶，推荐哪几款？",
    "S2_参数咨询": "这款茶的产地、香气、冲泡水温和适合人群是什么？",
    "S3_对比选购": "西湖龙井和碧螺春有什么区别，新手更适合买哪款？",
    "S4_价格活动": "现在买茶叶有没有优惠券，活动和价保规则是什么？",
    "S5_库存到货": "明前龙井没货了大概什么时候补货？",
    "S6_茶具兼容": "这个茶适合用盖碗还是紫砂壶，茶具怎么选？",
    "S7_适用场景": "送长辈茶礼应该选什么，包装有什么建议？",
    "S8_冲泡指引": "绿茶怎么泡不苦，水温和投茶量怎么控制？",
    "S9_设备配网": "智能泡茶机 M1 连不上 Wi-Fi 怎么重新配网？",
    "S10_APP功能": "茶山茶事 APP 怎么查溯源码和订单信息？",
    "S11_固件升级": "智能泡茶机提示固件升级，升级失败怎么办？",
    "S12_设备联动": "智能泡茶机能不能和 APP 远程控制联动？",
    "S13_保养维护": "紫砂壶、盖碗和泡茶机平时怎么清洁保养？",
    "S14_售后政策": "茶叶收到后发现包装破损，售后怎么处理？",
    "S15_退换货": "茶叶拆封后还能退吗，茶具退换货规则是什么？",
    "S16_物流配送": "茶叶下单后多久发货，能不能改地址？",
    "S17_发票会员": "怎么开发票，会员积分和优惠券能叠加吗？",
    "F1_故障报告": "泡茶机显示 E01 或茶汤太苦，应该怎么排查？",
    "F2_功能建议": "我希望 APP 增加茶叶库存提醒，可以反馈吗？",
    "F3_投诉吐槽": "客服回复太慢了，茶叶还没发货，我要投诉。",
    "C1_寒暄问候": "你好，在吗？",
    "C2_越界提问": "今天杭州天气怎么样，适合出门吗？",
}

TRAPS = {
    "S1_选购推荐": "budget_scene", "S2_参数咨询": "tea_parameter_exactness", "S3_对比选购": "tea_type_compare",
    "S4_价格活动": "price_policy_boundary", "S5_库存到货": "preorder_vs_logistics", "S6_茶具兼容": "teaware_compatibility",
    "S7_适用场景": "gift_scene", "S8_冲泡指引": "brew_parameter", "S9_设备配网": "wifi_pairing",
    "S10_APP功能": "app_path", "S11_固件升级": "upgrade_risk", "S12_设备联动": "device_linkage",
    "S13_保养维护": "maintenance_cycle", "S14_售后政策": "warranty_boundary", "S15_退换货": "return_boundary",
    "S16_物流配送": "order_status_boundary", "S17_发票会员": "invoice_member_boundary", "F1_故障报告": "fault_triage",
    "F2_功能建议": "no_rag_feedback", "F3_投诉吐槽": "emotion_handling", "C1_寒暄问候": "chat_no_rag", "C2_越界提问": "out_of_scope_no_rag",
}


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def build_catalog() -> dict:
    catalog = {}
    kb_dirs = {"01_product": "product", "02_manual": "manual", "03_policy": "policy", "04_faq": "faq"}
    for dirname, kb_key in kb_dirs.items():
        for path in sorted((KB_ROOT / dirname).rglob("*.md")):
            doc_id = path.stem
            parts = path.relative_to(KB_ROOT / dirname).parts
            category = parts[0] if len(parts) > 1 else "default"
            catalog[doc_id] = {
                "kb_key": kb_key,
                "category": category,
                "rel_path": str(path.relative_to(ROOT)).replace("\\", "/"),
            }
    counts = {kb: sum(1 for m in catalog.values() if m["kb_key"] == kb) for kb in ["product", "manual", "policy", "faq"]}
    if counts != {"product": 65, "manual": 25, "policy": 15, "faq": 10}:
        raise SystemExit(f"unexpected KB distribution: {counts}")
    if len(catalog) != 115:
        raise SystemExit(f"unexpected doc count: {len(catalog)}")
    return catalog


def docs_by(catalog: dict, kb: str, prefix: str | None = None, category: str | None = None) -> list[str]:
    ids = [doc_id for doc_id, meta in sorted(catalog.items()) if meta["kb_key"] == kb]
    if prefix:
        ids = [doc_id for doc_id in ids if doc_id.startswith(prefix)]
    if category:
        ids = [doc_id for doc_id in ids if catalog[doc_id]["category"] == category]
    return ids


def build_pools(catalog: dict) -> dict[str, list[str]]:
    prod = docs_by(catalog, "product", "PROD_TEA")
    guide = docs_by(catalog, "product", "GUIDE_TEA")
    app = docs_by(catalog, "manual", "MANUAL_APP")
    net = docs_by(catalog, "manual", "NET_TEA")
    manual = docs_by(catalog, "manual")
    warranty = docs_by(catalog, "policy", "POLICY_WARRANTY")
    returns = docs_by(catalog, "policy", "POLICY_RETURN")
    logistics = docs_by(catalog, "policy", "POLICY_LOGISTICS")
    invoice = docs_by(catalog, "policy", "POLICY_INVOICE")
    faq_err = docs_by(catalog, "faq", "FAQ_ERR")
    faq_trouble = docs_by(catalog, "faq", "FAQ_TROUBLE")
    return {
        "S1_选购推荐": guide[:2] + prod[:2],
        "S2_参数咨询": prod[:2],
        "S3_对比选购": guide[1:3] + prod[2:4],
        "S4_价格活动": invoice[2:3] + invoice[:1],
        "S5_库存到货": prod[3:5],
        "S6_茶具兼容": manual[-4:-2] + guide[12:13],
        "S7_适用场景": guide[4:6] + prod[5:6],
        "S8_冲泡指引": guide[9:11] + prod[6:7],
        "S9_设备配网": net[:2],
        "S10_APP功能": app[:2],
        "S11_固件升级": net[-1:] + app[-1:],
        "S12_设备联动": app[-1:] + net[:1],
        "S13_保养维护": manual[-6:-4] + faq_trouble[-1:],
        "S14_售后政策": warranty[:2],
        "S15_退换货": returns[:2] + faq_trouble[1:2],
        "S16_物流配送": logistics[:2],
        "S17_发票会员": invoice[:2],
        "F1_故障报告": faq_err[:2] + faq_trouble[:2],
    }


def sample(intent_code: str, intent_l1: str, kb: str, idx: int, pools: dict) -> dict:
    requires = kb != "system"
    docs = pools.get(intent_code, [])
    must = docs[:2] if requires else []
    nice = docs[2:] if requires and intent_code in {"S1_选购推荐", "S3_对比选购", "S6_茶具兼容", "S7_适用场景", "S8_冲泡指引", "S15_退换货", "F1_故障报告"} else []
    prefix = intent_code.split("_")[0]
    query = QUERY_TEMPLATES[intent_code].format(n=(idx % 5) + 2)
    return {
        "query_id": f"{prefix}-{idx:02d}",
        "query": query,
        "intent_l1": intent_l1,
        "intent_l2": intent_code,
        "difficulty": ["easy", "medium", "hard"][(idx - 1) % 3],
        "requires_rag": requires,
        "expected_answer_type": "no_rag" if not requires else "knowledge_answer",
        "expected_doc_ids": must,
        "expected_doc_ids_nice": nice,
        "trap_type": TRAPS[intent_code],
        "ground_truth": f"该问题应按茶品场景的 {intent_code} 处理。" + (f"核心证据来自 {', '.join(must)}。" if must else "应走系统兜底或人工反馈，不应强行检索知识库。"),
        "eval_metrics": ["intent_accuracy", "hit@5", "recall@5", "mrr@10"] if requires else ["intent_accuracy", "no_rag"],
    }


def build_datasets(pools: dict) -> None:
    rows = []
    for code, intent_l1, _cat, kb, count in INTENTS:
        for i in range(1, count + 1):
            rows.append(sample(code, intent_l1, kb, i, pools))
    if len(rows) != 150:
        raise SystemExit(f"unexpected sample count: {len(rows)}")
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
    for filename, data in [("eval_set_v1_all.jsonl", rows), ("eval_set_v1.jsonl", main)]:
        with (DATASET_ROOT / filename).open("w", encoding="utf-8", newline="\n") as f:
            for row in data:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")



# 覆盖上面的简版 sample/build_datasets：用于生成更像真实评测集的多问法样本。
# 设计目标：保持 150 条样本和原有 intent 分布不变，但每个 intent 使用多条自然问法，
# 同时按文档池轮换 expected_doc_ids，避免全量集只有少量重复问题。
QUERY_VARIANTS_BY_PREFIX = {
    "S1": [
        "预算 {n}00 元左右，想买一款适合日常喝的茶，推荐哪几款？",
        "新手第一次买茶，想要不容易踩雷、平时办公室能喝的，有什么选择？",
        "我想买一款清爽一点的茶送自己，价格别太高，应该怎么选？",
        "家里人喜欢喝淡一点的茶，想买两款对比试试，能推荐方向吗？",
        "想从绿茶、白茶、乌龙里选一款入门茶，按口味怎么判断？",
        "我准备囤一点日常口粮茶，预算有限，优先看哪些参数？",
        "不太懂茶叶等级和产地，购买前应该先问清楚哪些信息？",
        "想买适合冷泡和热泡都能用的茶，应该优先看哪类？",
        "给年轻同事选茶，不想太浓太苦，哪些产品方向更稳？",
    ],
    "S2": [
        "这款茶的产地、香气、冲泡水温和适合人群是什么？",
        "商品详情里的净含量、茶类、等级和保质期在哪里看？",
        "我想确认这款茶是不是明前茶，参数里应该看哪些字段？",
        "这款茶适合用多少度水冲泡，投茶量大概是多少？",
        "买之前想比较产区、香型和口感描述，这些信息从哪个文档判断？",
        "这个茶适合新手还是老茶客，详情页有什么依据？",
        "我想知道茶叶是否适合冷泡，应该查哪些参数？",
        "这款茶的储存条件和开封后饮用建议是什么？",
        "商品标注的香气和滋味分别代表什么，怎么理解？",
        "不同规格的茶叶在克重和适用场景上有什么差异？",
        "我只知道商品名，想查它的基础参数和冲泡建议。",
    ],
    "S3": [
        "西湖龙井和碧螺春有什么区别，新手更适合买哪款？",
        "绿茶和白茶在口感、香气、冲泡难度上怎么对比？",
        "红茶和乌龙茶哪个更适合办公室长期喝？",
        "同样是送礼，茶叶礼盒和散装口粮茶怎么选？",
        "预算差不多时，应该优先选产地、等级还是口感稳定性？",
        "我想在两款茶之间二选一，应该从哪些维度比较？",
        "新茶和陈茶适合的人群有什么不同？",
        "冷泡茶和热泡茶适合选择的茶类一样吗？",
        "想要香气明显但苦涩低，几类茶应该怎么排优先级？",
    ],
    "S4": [
        "现在买茶叶有没有优惠券，活动和价保规则是什么？",
        "满减、优惠券和会员折扣可以一起使用吗？",
        "活动价下单后又降价了，能申请价保吗？",
        "茶具和茶叶一起买，优惠规则是分开算还是合并算？",
        "直播间优惠和店铺券冲突时以哪个为准？",
        "促销活动结束后还能补发优惠吗？",
        "使用积分抵扣后还能享受满减或价保吗？",
    ],
    "S5": [
        "明前龙井没货了，大概什么时候补货？",
        "页面显示缺货，我能不能先预约到货提醒？",
        "同一款茶不同规格库存不一样，应该怎么确认？",
        "想买礼盒但暂时无货，可以推荐替代款吗？",
        "预售商品什么时候发货，和现货有什么区别？",
        "库存紧张时下单成功是否代表一定能发出？",
    ],
    "S6": [
        "这个茶适合用盖碗还是紫砂壶，茶具怎么选？",
        "绿茶能不能用保温杯泡，会不会影响口感？",
        "智能泡茶机适合冲泡哪些茶，不适合哪些茶？",
        "冷泡壶、玻璃杯和盖碗分别适合什么场景？",
        "紫砂壶能不能混泡不同茶类，需要注意什么？",
        "茶水分离杯适合办公室喝茶吗？",
        "新手没有专业茶具，最少需要准备哪些工具？",
    ],
    "S7": [
        "送长辈茶礼应该选什么，包装有什么建议？",
        "商务拜访送茶，应该优先考虑品牌、包装还是口味？",
        "给不常喝茶的人送礼，哪类茶接受度更高？",
        "办公室自饮和家庭待客分别适合什么茶？",
        "夏天想冷泡，应该选哪些茶更合适？",
        "晚上喝茶怕影响睡眠，选购时要注意什么？",
        "给朋友生日送茶，怎么避免选得太专业不好泡？",
    ],
    "S8": [
        "绿茶怎么泡不苦，水温和投茶量怎么控制？",
        "红茶冲出来发涩，应该调水温还是缩短时间？",
        "乌龙茶第一泡要不要洗茶，出汤时间怎么把握？",
        "冷泡茶需要泡多久，茶水比例怎么控制？",
        "白茶适合煮还是泡，不同方式有什么区别？",
        "茶汤太淡时应该加茶叶还是延长浸泡？",
        "新手用玻璃杯泡茶，怎样避免久闷变苦？",
        "同一款茶第二泡第三泡的时间应该怎么调整？",
    ],
    "S9": [
        "智能泡茶机 M1 连不上 Wi-Fi 怎么重新配网？",
        "设备换了路由器后无法联网，应该怎么操作？",
        "APP 搜不到泡茶机，配网前要检查什么？",
        "设备只支持 2.4G Wi-Fi 吗，5G 网络能不能用？",
        "配网一直超时，是距离、密码还是权限问题？",
        "手机蓝牙开了但设备不出现，怎么排查？",
        "重置网络配置会不会清空泡茶参数？",
    ],
    "S10": [
        "茶山茶事 APP 怎么查溯源码和订单信息？",
        "APP 里在哪里查看我的优惠券和会员积分？",
        "想查看历史购买记录和发票入口，应该点哪里？",
        "APP 能不能保存常用冲泡参数？",
        "如何在 APP 里绑定智能泡茶设备？",
        "收货地址和默认地址在哪里修改？",
        "APP 里怎么查看售后进度？",
    ],
    "S11": [
        "智能泡茶机提示固件升级，升级失败怎么办？",
        "固件升级过程中断电了，设备还能恢复吗？",
        "APP 提示有新版本固件，升级前要注意什么？",
        "升级后设备参数丢失，应该怎么找回？",
        "固件下载很慢或卡住，应该怎么排查网络？",
    ],
    "S12": [
        "智能泡茶机能不能和 APP 远程控制联动？",
        "设备可以设置定时泡茶或预约提醒吗？",
        "多个家庭成员能不能共同控制同一台设备？",
        "泡茶机和温控壶能否联动使用？",
        "APP 场景模式里能不能保存不同茶类参数？",
        "设备离线时还能执行之前设置的自动程序吗？",
    ],
    "S13": [
        "紫砂壶、盖碗和泡茶机平时怎么清洁保养？",
        "茶垢比较重时能不能用洗洁精清洗？",
        "泡茶机水路多久清洗一次，怎么防止异味？",
        "玻璃茶具和陶瓷茶具清洁方式有什么不同？",
        "长期不用的茶具应该怎么晾干和收纳？",
        "紫砂壶使用后要不要完全晒干？",
        "茶叶开封后如何保存，受潮了还能喝吗？",
        "泡茶机提示清洁或除垢时应该按什么步骤处理？",
        "不同茶类混用茶具会不会串味，怎么避免？",
    ],
    "S14": [
        "茶叶收到后发现包装破损，售后怎么处理？",
        "茶具运输途中裂了，应该提供哪些凭证申请售后？",
        "收到的茶叶和订单不一致，可以申请补发吗？",
        "过了签收时间才发现问题，还能走售后吗？",
        "智能泡茶机保修范围包括哪些情况？",
        "人为损坏和质量问题售后规则有什么区别？",
        "售后审核一般需要多久，会怎么通知结果？",
        "礼盒外包装压痕但内物完好，能不能申请处理？",
    ],
    "S15": [
        "茶叶拆封后还能退吗，茶具退换货规则是什么？",
        "七天无理由适用于茶叶和茶具吗？",
        "退货时赠品、发票和包装需要一起寄回吗？",
        "已经使用过的泡茶机还能申请退换吗？",
        "茶叶口味不喜欢能不能作为退货理由？",
        "换货和退货的运费分别由谁承担？",
        "申请退款后多久到账，原路退回吗？",
    ],
    "S16": [
        "茶叶下单后多久发货，能不能改地址？",
        "订单已经出库了还能拦截或修改收货信息吗？",
        "偏远地区配送时效和运费有什么规则？",
        "快递显示签收但我没收到，应该怎么处理？",
        "预售茶叶和现货一起下单会分开发货吗？",
        "物流长时间不更新，需要联系谁核查？",
    ],
    "S17": [
        "怎么开发票，会员积分和优惠券能叠加吗？",
        "企业发票需要填写哪些抬头和税号信息？",
        "积分什么时候到账，退货后积分会扣回吗？",
        "优惠券过期了还能补发或恢复吗？",
        "会员等级权益和活动优惠有什么关系？",
        "电子发票在哪里下载，能不能重新发送？",
    ],
    "F1": [
        "泡茶机显示 E01 或茶汤太苦，应该怎么排查？",
        "设备出水异常或水量不准，先检查哪些地方？",
        "APP 显示设备离线但机器正常亮灯，怎么办？",
        "泡茶机有异味或茶汤浑浊，可能是什么原因？",
        "温控壶加热慢或者不保温，应该怎么判断故障？",
    ],
    "F2": [
        "我希望 APP 增加茶叶库存提醒，可以反馈吗？",
        "能不能建议增加按口味自动推荐茶叶的功能？",
        "我想提交一个包装改进建议，应该走什么入口？",
        "希望会员积分规则更清楚，这类建议怎么反馈？",
        "可以建议设备增加夜间静音模式吗？",
    ],
    "F3": [
        "客服回复太慢了，茶叶还没发货，我要投诉。",
        "收到货体验不好，我想表达不满并要求处理。",
        "售后一直没有进展，如何升级投诉？",
        "物流延误影响送礼了，我希望有人跟进。",
        "多次沟通没有解决问题，我要人工介入。",
    ],
    "C1": [
        "你好，在吗？",
        "你是谁，可以帮我做什么？",
        "我想随便聊聊茶，有什么建议？",
        "先不买东西，能介绍一下你能回答哪些问题吗？",
        "晚上好，帮我看看茶相关问题可以吗？",
    ],
    "C2": [
        "今天杭州天气怎么样，适合出门吗？",
        "帮我写一段和茶无关的工作总结。",
        "股票明天会涨吗，你能预测一下吗？",
        "给我查一下附近餐厅排队情况。",
        "请回答一个和茶品客服完全无关的问题。",
    ],
}


def rotate_docs(docs: list[str], idx: int, size: int) -> list[str]:
    if not docs:
        return []
    start = (idx - 1) % len(docs)
    return [docs[(start + offset) % len(docs)] for offset in range(min(size, len(docs)))]


def sample(intent_code: str, intent_l1: str, kb: str, idx: int, pools: dict) -> dict:
    requires = kb != "system"
    docs = pools.get(intent_code, [])
    must = rotate_docs(docs, idx, 2) if requires else []
    nice = rotate_docs([doc for doc in docs if doc not in must], idx, 2) if requires else []
    prefix = intent_code.split("_")[0]
    variants = QUERY_VARIANTS_BY_PREFIX.get(prefix, [QUERY_TEMPLATES[intent_code]])
    query = variants[(idx - 1) % len(variants)].format(n=(idx % 5) + 2)
    return {
        "query_id": f"{prefix}-{idx:02d}",
        "query": query,
        "intent_l1": intent_l1,
        "intent_l2": intent_code,
        "difficulty": ["easy", "medium", "hard"][(idx - 1) % 3],
        "requires_rag": requires,
        "expected_answer_type": "no_rag" if not requires else "knowledge_answer",
        "expected_doc_ids": must,
        "expected_doc_ids_nice": nice,
        "trap_type": TRAPS[intent_code],
        "ground_truth": f"该问题应按茶品场景的 {intent_code} 处理。" + (f"核心证据来自 {', '.join(must)}。" if must else "应走系统兜底或人工反馈，不应强行检索知识库。"),
        "eval_metrics": ["intent_accuracy", "hit@5", "recall@5", "mrr@10"] if requires else ["intent_accuracy", "no_rag"],
    }


def build_datasets(pools: dict) -> None:
    rows = []
    for code, intent_l1, _cat, kb, count in INTENTS:
        for i in range(1, count + 1):
            rows.append(sample(code, intent_l1, kb, i, pools))
    if len(rows) != 150:
        raise SystemExit(f"unexpected sample count: {len(rows)}")
    if len({row["query"] for row in rows}) != len(rows):
        raise SystemExit("duplicate query found in generated eval_set_v1_all.jsonl")

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
    for filename, data in [("eval_set_v1_all.jsonl", rows), ("eval_set_v1.jsonl", main)]:
        with (DATASET_ROOT / filename).open("w", encoding="utf-8", newline="\n") as f:
            for row in data:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
def main() -> None:
    catalog = build_catalog()
    pools = build_pools(catalog)
    build_datasets(pools)
    write_json(ROOT / "kb_specs.json", KB_SPECS)
    write_json(ROOT / "doc_catalog.json", catalog)
    write_json(ROOT / "intent_tree_spec.json", {"domains": DOMAINS, "categories": CATEGORIES, "intents": INTENTS})
    write_json(ROOT / "tea_eval_doc_pools.json", pools)
    print("generated tea eval assets: doc_catalog, kb_specs, intent_tree_spec, 20/150 datasets")


if __name__ == "__main__":
    main()