from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET_PATH = ROOT / "datasets" / "eval_set_qingju_150.jsonl"


STATIC_CASES = {
    "QJ_PRODUCT_RECOMMEND": [
        ("90 平两居室养猫，地板和沙发缝里毛发多，清居无线清洁机优先看什么？", ["GUIDE_QJ_VAC_001", "QJ-VC-002"]),
        ("租房一居室收纳位置很小，想买清居清洁设备，应该先确认哪些选购条件？", ["GUIDE_QJ_VAC_001", "QJ-VC-003"]),
        ("家里有短绒地毯和木地板，选清居无线清洁系列时怎样避免只按价格决定？", ["GUIDE_QJ_VAC_001", "QJ-VC-005"]),
        ("老人每天只想做轻量清洁，清居产品推荐需要问清哪些使用习惯？", ["GUIDE_QJ_VAC_001", "QJ-VC-001"]),
        ("新装修的卧室感觉空气闷，清居空气护理产品应该从房间条件还是型号序号开始选？", ["GUIDE_QJ_AIR_001", "QJ-AIR-002"]),
        ("卧室面积不大但经常不开窗，挑选清居空气护理机要比较哪些维度？", ["GUIDE_QJ_AIR_001", "QJ-AIR-005"]),
        ("我想给朋友送居家个护礼物，清居柔风系列推荐前要了解哪些需求？", ["GUIDE_QJ_CARE_001", "QJ-CARE-003"]),
        ("工作日早晨时间紧，个护造型器该按什么场景选择，而不是直接说哪台最好？", ["GUIDE_QJ_CARE_001", "QJ-CARE-006"]),
        ("家里既有宠物毛发又觉得卧室空气不流通，清居方案应如何拆分推荐？", ["GUIDE_QJ_COMPARE_001", "GUIDE_QJ_VAC_001", "GUIDE_QJ_AIR_001"]),
        ("预算有限，想在清洁和空气护理设备中先买一类，清居平台应怎样帮助我判断优先级？", ["GUIDE_QJ_COMPARE_001"]),
    ],
    "QJ_PRODUCT_SPEC": [
        ("QJ-VC-001 的展示型号、所属品类和适用场景是什么？", ["QJ-VC-001"]),
        ("请说明 QJ-VC-008 在清居资料里的静态咨询边界。", ["QJ-VC-008"]),
        ("QJ-AIR-003 对应的型号和产品定位怎么查？", ["QJ-AIR-003"]),
        ("QJ-AIR-007 是什么类别的清居商品，哪些信息不能从商品页直接得出？", ["QJ-AIR-007"]),
        ("QJ-CARE-002 的型号、场景和安全咨询边界分别是什么？", ["QJ-CARE-002"]),
        ("想了解 QJ-CARE-008 的静态资料，应重点看哪几类事实？", ["QJ-CARE-008"]),
        ("QJ-ACC-004 是什么品类，为什么不能只根据名称判断它能否适配主机？", ["QJ-ACC-004"]),
        ("QJ-ACC-016 的商品资料能回答什么，不能回答什么？", ["QJ-ACC-016"]),
        ("QJ-GIFT-001 的资料属于礼盒还是实时服务记录？", ["QJ-GIFT-001"]),
        ("QJ-GIFT-006 的目录价能否直接当作当前下单价？请按资料边界说明。", ["QJ-GIFT-006"]),
    ],
    "QJ_PRODUCT_COMPARE": [
        ("VC-A2 与 VC-A8 做选购比较时，应该优先比较哪些静态维度？", ["GUIDE_QJ_COMPARE_001", "QJ-VC-002", "QJ-VC-008"]),
        ("QJ-VC-001 和 QJ-VC-005 不看实时价格时，怎样比较更合理？", ["GUIDE_QJ_VAC_001", "QJ-VC-001", "QJ-VC-005"]),
        ("空气护理机和无线清洁机分别解决什么居家问题，不能混为一谈的原因是什么？", ["GUIDE_QJ_COMPARE_001"]),
        ("AP-A2 与 AP-A6 的选择应该结合哪些房间和维护条件？", ["GUIDE_QJ_AIR_001", "QJ-AIR-002", "QJ-AIR-006"]),
        ("HC-A1 和 HC-A7 的对比应关注什么，哪些内容不能夸大为护理功效？", ["GUIDE_QJ_CARE_001", "QJ-CARE-001", "QJ-CARE-007"]),
        ("对宠物家庭而言，先买空气护理还是无线清洁，怎么从场景而非价格判断？", ["GUIDE_QJ_COMPARE_001", "GUIDE_QJ_VAC_001", "GUIDE_QJ_AIR_001"]),
        ("小户型想降低收纳压力，清洁设备与个护设备的选择逻辑有什么不同？", ["GUIDE_QJ_COMPARE_001", "GUIDE_QJ_VAC_001", "GUIDE_QJ_CARE_001"]),
        ("用户同时问 QJ-VC-004 的适用场景和 QJ-AIR-004 的维护重点，系统应如何分别回答？", ["QJ-VC-004", "QJ-AIR-004", "GUIDE_QJ_COMPARE_001"]),
        ("无线清洁机的耗材维护和空气护理机的滤网维护，有哪些共同点和差异？", ["GUIDE_QJ_VAC_001", "GUIDE_QJ_AIR_001"]),
        ("买礼盒服务和买主机时，咨询资料的重点为何不同？", ["QJ-GIFT-002", "GUIDE_QJ_COMPARE_001"]),
    ],
    "QJ_USAGE_GUIDE": [
        ("无线清洁机第一次使用前，需要检查哪些部件和环境？", ["MANUAL_QJ_VAC_001"]),
        ("清居无线清洁机清理尘杯前，正确的安全顺序是什么？", ["MANUAL_QJ_VAC_001"]),
        ("空气护理机应该怎样摆放，哪些位置不适合？", ["MANUAL_QJ_AIR_001"]),
        ("空气护理机提示维护后，断电检查时要注意什么？", ["MANUAL_QJ_AIR_001"]),
        ("个护造型器使用结束后应该怎样冷却、清洁和收纳？", ["MANUAL_QJ_CARE_001"]),
        ("个护设备在潮湿环境中使用有哪些风险，资料建议怎么做？", ["MANUAL_QJ_CARE_001"]),
        ("清居设备首次连接 App 前，手机和设备分别需要确认什么？", ["MANUAL_QJ_APP_001"]),
        ("App 找不到设备时，应该先排查网络、权限还是订单状态？", ["MANUAL_QJ_APP_001"]),
        ("清洁机滤网清洗后能否马上装回使用？", ["MANUAL_QJ_VAC_001"]),
        ("空气护理机进风口被窗帘遮住会怎样处理？", ["MANUAL_QJ_AIR_001"]),
    ],
    "QJ_FAQ_TROUBLESHOOT": [
        ("无线清洁机吸力突然变弱，安全的第一轮排查有哪些？", ["FAQ_QJ_VAC_001", "MANUAL_QJ_VAC_001"]),
        ("清洁机刷头不转时，可以先看哪些非拆机原因？", ["FAQ_QJ_VAC_001"]),
        ("清洁设备有持续异响和焦味，应该继续试机还是停止使用？", ["FAQ_QJ_VAC_001"]),
        ("空气护理机风量变小，怎样检查滤网和进风口？", ["FAQ_QJ_AIR_001", "MANUAL_QJ_AIR_001"]),
        ("空气护理机有烟味时，FAQ 建议用户做什么？", ["FAQ_QJ_AIR_001"]),
        ("个护造型器无法启动，断电后可以检查什么？", ["FAQ_QJ_CARE_001"]),
        ("个护设备线缆破损后还可以自行绕过保护继续用吗？", ["FAQ_QJ_CARE_001"]),
        ("清居 App 设备反复离线，排查时不该向用户索取哪些信息？", ["FAQ_QJ_APP_001", "MANUAL_QJ_APP_001"]),
        ("App 配网重复失败时，应该记录哪些信息再联系售后？", ["FAQ_QJ_APP_001"]),
        ("用户只说设备不能用，没有型号和现象时，客服应该先问什么？", ["FAQ_QJ_VAC_001", "FAQ_QJ_AIR_001", "FAQ_QJ_CARE_001"]),
    ],
    "QJ_AFTERSALES_POLICY": [
        ("清居设备发生故障后，申请售后的通用路径是什么？", ["POLICY_QJ_WARRANTY_001"]),
        ("保修咨询前，用户需要准备哪些商品和故障信息？", ["POLICY_QJ_WARRANTY_001"]),
        ("设备有焦味时，售后政策和 FAQ 各自解决什么问题？", ["POLICY_QJ_WARRANTY_001", "FAQ_QJ_VAC_001"]),
        ("收到商品后想退货，清居应先确认哪些条件？", ["POLICY_QJ_RETURN_001"]),
        ("订单已发货后，静态配送政策能否直接告诉我当前承运商？", ["POLICY_QJ_RETURN_001"]),
        ("用户想修改配送信息，政策资料应怎样说明下一步？", ["POLICY_QJ_RETURN_001"]),
        ("开票时需要用户核对哪些信息，系统不能替用户做什么？", ["POLICY_QJ_INVOICE_001"]),
        ("礼盒权益是否已生效，为什么不能只查商品介绍？", ["POLICY_QJ_INVOICE_001", "QJ-GIFT-001"]),
        ("查询订单时为什么只应使用订单号和必要的手机号后四位？", ["POLICY_QJ_PRIVACY_001"]),
        ("订单号不完整时，清居客服应该猜测相近订单还是请用户核对？", ["POLICY_QJ_PRIVACY_001"]),
    ],
}

MCP_CASES = {
    "QJ_ORDER_STATUS": [
        "订单 QJ20260713001 的支付和发货状态分别是什么？", "请查询 QJ20260713002 的商品明细和订单进度。",
        "QJ20260713003 是否已经签收？", "订单 QJ20260713004 为什么显示取消，请返回当前状态。",
        "QJ20260713005 的售后状态是什么？", "QJ20260713006 目前是全部发货还是部分发货？",
        "查询 QJ20260713007 的支付、订单和售后状态。", "订单 QJ20260713008 现在进行到哪一步？",
        "QJ20260713009 是否已经付款？", "订单 QJ20260713010 的换货处理到了什么状态？",
    ],
    "QJ_ORDER_LOGISTICS": [
        "QJ20260713001 为什么还没有物流单号？", "QJ20260713002 的承运商和最新物流节点是什么？",
        "订单 QJ20260713003 的物流是否显示签收？", "QJ20260713005 的包裹由哪家快递配送？",
        "QJ20260713006 为什么提示部分发货，预计什么时候送达？", "QJ20260713007 当前运输到了哪个节点？",
        "QJ20260713008 的运单号、派送状态和预计送达日期是什么？", "QJ20260713010 是否已经完成签收？",
        "QJ20260713011 未发货时，物流查询会返回什么？", "QJ20260713012 的发货仓和签收记录是什么？",
    ],
    "QJ_INVENTORY": [
        "请查询 SKU QJ-VC-001 的当前可售库存和售价。", "QJ-VC-008 现在还有多少库存？",
        "QJ-AIR-003 的型号、价格和库存分别是多少？", "帮我查 QJ-AIR-008 是否可售。",
        "QJ-CARE-002 当前库存够不够，商品属于什么分类？", "QJ-CARE-007 的实时售价是多少？",
        "配件 QJ-ACC-002 现在能不能购买？", "QJ-ACC-012 的商品名称和库存是什么？",
        "礼盒 QJ-GIFT-001 的实时库存和价格请查询。", "QJ-GIFT-006 是否还有现货？",
    ],
    "QJ_ACCESSORY_COMPATIBILITY": [
        "VC-A2 能使用 QJ-ACC-002 吗？", "VC-A6 与 QJ-ACC-005 是否兼容？",
        "VC-A9 能不能搭配 QJ-ACC-008？", "AP-A1 是否适配 QJ-ACC-010？",
        "AP-A6 与 QJ-ACC-014 的兼容结论是什么？", "HC-A2 能使用 QJ-ACC-016 吗？",
        "HC-A7 配 QJ-ACC-018 是否合适？", "VC-A4 是否支持 QJ-ACC-006？",
        "AP-A4 能否使用 QJ-ACC-013？", "VC-A10 和 QJ-ACC-001 是否兼容？",
    ],
    "QJ_AMAP_GEOCODE": [
        "查询杭州东站的坐标和标准化地址。", "上海虹桥火车站的经纬度是什么？",
        "请定位北京首都国际机场。", "广州塔的地址坐标能查到吗？",
        "深圳北站在哪里，返回坐标信息。", "成都东站的标准地址是什么？",
        "帮我查南京夫子庙的经纬度。", "武汉大学的位置坐标是多少？",
        "西安钟楼的标准化地址请查询。", "苏州园林博物馆的坐标在哪里？",
    ],
    "QJ_AMAP_ROUTE": [
        "从杭州东站开车到西湖景区大约多久？", "上海虹桥站驾车到外滩有多远？",
        "北京南站到故宫的驾车路线时间是多少？", "广州南站开车去广州塔要多久？",
        "深圳北站到世界之窗的驾车距离和时间是什么？", "成都东站开车到宽窄巷子怎么走？",
        "南京站到夫子庙的驾车预计耗时是多少？", "武汉站到黄鹤楼的车程大概多久？",
        "西安北站到钟楼的驾车路线如何？", "苏州站开车去拙政园需要多少时间？",
    ],
    "QJ_AMAP_WEATHER": [
        "杭州西湖区现在天气怎么样？", "上海浦东新区的实时天气请查询。",
        "北京朝阳区目前温度和天气状况是什么？", "广州天河区现在适合外出吗，先查天气。",
        "深圳南山区的实时天气如何？", "成都武侯区今天的天气情况是什么？",
        "南京玄武区当前天气查询一下。", "武汉武昌区现在天气和风力怎样？",
        "西安碑林区的实时天气是什么？", "苏州姑苏区现在是否有降水信息？",
    ],
}

SYSTEM_CASES = {
    "QJ_GREETING": ["你好", "在吗？", "早上好，清居助手", "晚上好", "请介绍一下你的能力", "你能提供哪些清居服务？", "我第一次使用，先和我打个招呼", "嗨，能听到吗？", "谢谢你的说明", "好的，明白了"],
    "QJ_OUT_OF_SCOPE": ["帮我预测明天的股票涨跌。", "给我写一篇科幻小说。", "这道高数题怎么求导？", "推荐一家北京的火锅店。", "帮我制定减肥处方。", "解释一下量子纠缠。", "帮我翻译一篇英文合同。", "今天国际金价会不会上涨？", "写一段 Java 并发代码。", "帮我规划环球旅行预算。"],
}

EXPECTED_TOOL = {
    "QJ_ORDER_STATUS": "qingju_order_query", "QJ_ORDER_LOGISTICS": "qingju_logistics_query",
    "QJ_INVENTORY": "qingju_inventory_query", "QJ_ACCESSORY_COMPATIBILITY": "qingju_accessory_compatibility",
    "QJ_AMAP_GEOCODE": "amap_geocode", "QJ_AMAP_ROUTE": "amap_driving_route", "QJ_AMAP_WEATHER": "amap_weather_query",
}


def make_row(query_id: str, intent: str, query: str, expected_docs: list[str] | None = None) -> dict:
    is_static = intent in STATIC_CASES
    is_mcp = intent in EXPECTED_TOOL
    intent_l1 = "QINGJU_KB" if is_static else "QINGJU_REALTIME" if is_mcp and not intent.startswith("QJ_AMAP") else "LOCAL_SERVICE" if is_mcp else "QINGJU_SYSTEM"
    difficulty = ["easy", "medium", "hard"][(int(query_id[-3:]) - 1) % 3]
    return {
        "query_id": query_id,
        "query": query,
        "intent_l1": intent_l1,
        "intent_l2": intent,
        "difficulty": difficulty,
        "requires_rag": is_static,
        "expected_answer_type": "knowledge_answer" if is_static else "mcp_answer" if is_mcp else "no_rag",
        "expected_doc_ids": expected_docs or [],
        "expected_mcp_tool_id": EXPECTED_TOOL.get(intent),
        "ground_truth": (
            "回答应引用期望清居知识资料；资料不足时要说明边界，不能编造实时事实。" if is_static
            else f"必须通过 MCP 工具 {EXPECTED_TOOL[intent]} 查询，不得用静态资料伪造实时结果。" if is_mcp
            else "应礼貌问候或说明能力范围，不应触发知识库检索或 MCP 工具。"
        ),
        "eval_metrics": (["intent_accuracy", "hit@5", "recall@5", "mrr@10", "kb_route_accuracy"] if is_static
                         else ["intent_accuracy", "mcp_route_accuracy", "mcp_invocation_rate"] if is_mcp
                         else ["intent_accuracy", "no_retrieval_accuracy"]),
    }


def build_rows() -> list[dict]:
    rows = []
    serial = 1
    for intent, cases in STATIC_CASES.items():
        for query, expected_docs in cases:
            rows.append(make_row(f"QJ150-{serial:03d}", intent, query, expected_docs))
            serial += 1
    for intent, queries in MCP_CASES.items():
        for query in queries:
            rows.append(make_row(f"QJ150-{serial:03d}", intent, query))
            serial += 1
    for intent, queries in SYSTEM_CASES.items():
        for query in queries:
            rows.append(make_row(f"QJ150-{serial:03d}", intent, query))
            serial += 1
    if len(rows) != 150:
        raise ValueError(f"expected 150 rows, got {len(rows)}")
    if len({row['query'] for row in rows}) != 150:
        raise ValueError("evaluation questions must be unique")
    distribution = {}
    for row in rows:
        distribution[row["intent_l2"]] = distribution.get(row["intent_l2"], 0) + 1
    if any(count != 10 for count in distribution.values()):
        raise ValueError(f"unexpected intent distribution: {distribution}")
    return rows


def main() -> None:
    rows = build_rows()
    DATASET_PATH.parent.mkdir(parents=True, exist_ok=True)
    with DATASET_PATH.open("w", encoding="utf-8", newline="\n") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"generated {len(rows)} unique Qingju evaluation questions: {DATASET_PATH}")


if __name__ == "__main__":
    main()
