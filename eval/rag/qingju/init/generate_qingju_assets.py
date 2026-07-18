from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "knowledge_base"
DATASET_ROOT = ROOT / "datasets"

KB_SPECS = {
    "product": {"name": "清居智能 商品知识库", "collection_name": "kb-qingju-product", "embedding_model": "qwen-emb-8b"},
    "manual": {"name": "清居智能 使用手册库", "collection_name": "kb-qingju-manual", "embedding_model": "qwen-emb-8b"},
    "policy": {"name": "清居智能 服务政策库", "collection_name": "kb-qingju-policy", "embedding_model": "qwen-emb-8b"},
    "faq": {"name": "清居智能 故障 FAQ 库", "collection_name": "kb-qingju-faq", "embedding_model": "qwen-emb-8b"},
}

TREE_SPEC = {
    "domains": {
        "QINGJU_KB": "清居智能知识咨询",
        "QINGJU_REALTIME": "清居智能实时服务",
        "LOCAL_SERVICE": "本地出行服务",
        "QINGJU_SYSTEM": "清居智能会话兜底",
    },
    "categories": {
        "QINGJU_PRESALE": ["QINGJU_KB", "商品选购与规格"],
        "QINGJU_USAGE": ["QINGJU_KB", "使用维护与故障"],
        "QINGJU_AFTERSALES": ["QINGJU_KB", "售后与服务政策"],
        "QINGJU_ORDER": ["QINGJU_REALTIME", "订单库存与配件"],
        "QINGJU_AMAP": ["LOCAL_SERVICE", "地址路线与天气"],
        "QINGJU_CHAT": ["QINGJU_SYSTEM", "闲聊与边界"],
    },
    "intents": [
        {
            "code": "QJ_PRODUCT_RECOMMEND", "name": "清居商品选购", "parent": "QINGJU_PRESALE", "kind": 0, "kb_key": "product",
            "description": "预算、家庭面积、地面类型、宠物和清洁需求驱动的清居智能家电选购建议。",
            "examples": ["80 平两居室，有宠物毛发，适合选哪款无线清洁机？", "卧室空气闷，清居空气护理机怎么选？"],
        },
        {
            "code": "QJ_PRODUCT_SPEC", "name": "清居规格参数", "parent": "QINGJU_PRESALE", "kind": 0, "kb_key": "product",
            "description": "查询指定清居 SKU 或型号的静态分类、定位、使用边界和适用场景，不回答实时库存。",
            "examples": ["QJ-VC-008 对应什么型号？", "HC-A3 适合什么造型场景？"],
        },
        {
            "code": "QJ_PRODUCT_COMPARE", "name": "清居商品对比", "parent": "QINGJU_PRESALE", "kind": 0, "kb_key": "product",
            "description": "比较无线清洁、空气护理和个护产品的适用场景与选购维度。",
            "examples": ["VC-A2 和 VC-A8 的选购侧重点有什么区别？", "空气护理机和无线清洁机分别解决什么问题？"],
        },
        {
            "code": "QJ_USAGE_GUIDE", "name": "清居使用维护", "parent": "QINGJU_USAGE", "kind": 0, "kb_key": "manual",
            "description": "首次使用、滤网清洁、耗材更换、连接 App 和日常维护的操作说明。",
            "examples": ["无线清洁机首次使用前要检查什么？", "空气护理机的滤网多久检查一次？"],
        },
        {
            "code": "QJ_FAQ_TROUBLESHOOT", "name": "清居故障排查", "parent": "QINGJU_USAGE", "kind": 0, "kb_key": "faq",
            "description": "设备异常、吸力下降、异味、连接失败等问题的安全排查，不替代人工维修。",
            "examples": ["清洁机吸力变弱怎么排查？", "空气护理机提示需要维护怎么办？"],
        },
        {
            "code": "QJ_AFTERSALES_POLICY", "name": "清居售后政策", "parent": "QINGJU_AFTERSALES", "kind": 0, "kb_key": "policy",
            "description": "保修、退换货、配送、发票与服务申请的静态政策说明。",
            "examples": ["清居设备保修从什么时候开始计算？", "收到商品后如何申请退货？"],
        },
        {
            "code": "QJ_ORDER_STATUS", "name": "清居订单状态", "parent": "QINGJU_ORDER", "kind": 2, "mcp_tool_id": "qingju_order_query",
            "description": "根据订单号查询模拟商城订单状态、支付状态、商品明细和售后状态。", "examples": ["订单 QJ20260713008 现在是什么状态？"],
        },
        {
            "code": "QJ_ORDER_LOGISTICS", "name": "清居订单物流", "parent": "QINGJU_ORDER", "kind": 2, "mcp_tool_id": "qingju_logistics_query",
            "description": "根据订单号查询模拟商城物流承运商、运单号、物流节点与预计送达。", "examples": ["QJ20260713008 到哪里了？"],
        },
        {
            "code": "QJ_INVENTORY", "name": "清居实时库存", "parent": "QINGJU_ORDER", "kind": 2, "mcp_tool_id": "qingju_inventory_query",
            "description": "根据 SKU 查询模拟商城当前售价和可售库存，不能用商品知识库代替。", "examples": ["QJ-VC-008 还有库存吗？"],
        },
        {
            "code": "QJ_ACCESSORY_COMPATIBILITY", "name": "清居配件兼容", "parent": "QINGJU_ORDER", "kind": 2, "mcp_tool_id": "qingju_accessory_compatibility",
            "description": "根据主机型号和配件 SKU 查询模拟商城维护的兼容关系。", "examples": ["VC-A2 能使用 QJ-ACC-002 吗？"],
        },
        {
            "code": "QJ_AMAP_GEOCODE", "name": "地址坐标查询", "parent": "QINGJU_AMAP", "kind": 2, "mcp_tool_id": "amap_geocode",
            "description": "将用户提供的地址转换为坐标或标准化地址信息。", "examples": ["杭州东站的经纬度是什么？"],
        },
        {
            "code": "QJ_AMAP_ROUTE", "name": "驾车路线查询", "parent": "QINGJU_AMAP", "kind": 2, "mcp_tool_id": "amap_driving_route",
            "description": "查询两个地点之间的驾车距离、预计时间和路线信息。", "examples": ["从杭州东站开车到西湖景区要多久？"],
        },
        {
            "code": "QJ_AMAP_WEATHER", "name": "实时天气查询", "parent": "QINGJU_AMAP", "kind": 2, "mcp_tool_id": "amap_weather_query",
            "description": "查询城市或区县的实时天气；需要已在本地配置高德 Web 服务 Key。", "examples": ["杭州西湖区现在天气怎么样？"],
        },
        {
            "code": "QJ_GREETING", "name": "清居问候", "parent": "QINGJU_CHAT", "kind": 1,
            "description": "普通问候、能力介绍与无业务事实的会话。", "examples": ["你好", "你能帮我做什么？"],
        },
        {
            "code": "QJ_OUT_OF_SCOPE", "name": "清居范围外问题", "parent": "QINGJU_CHAT", "kind": 1,
            "description": "与清居商品、订单、物流、配件或已配置地图服务无关的问题，应礼貌说明能力范围。", "examples": ["帮我写一篇小说", "今天股票会涨吗？"],
        },
    ],
}


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def product_rows() -> list[dict]:
    rows = []
    groups = [
        ("QJ-VC", "VC-A", "清居灵吸无线清洁机", "无线清洁", 10, 1899, "地板、地毯与缝隙的日常清洁"),
        ("QJ-AIR", "AP-A", "清居澄风空气护理机", "空气护理", 8, 1599, "卧室、客厅等室内空气循环与滤网维护"),
        ("QJ-CARE", "HC-A", "清居柔风个护造型器", "个护美发", 8, 899, "居家日常造型与个护收纳"),
        ("QJ-ACC", "ACC-", "清居耗材配件", "耗材配件", 18, 79, "设备耗材更换与指定型号适配"),
        ("QJ-GIFT", "GIFT-", "清居延保礼盒", "礼盒与延保", 6, 199, "礼赠与服务权益说明"),
    ]
    for sku_prefix, model_prefix, name_prefix, category, count, base_price, scene in groups:
        for index in range(1, count + 1):
            rows.append({
                "sku": f"{sku_prefix}-{index:03d}",
                "model": f"{model_prefix}{index}",
                "name": f"{name_prefix} {index} 号",
                "category": category,
                "price": base_price + (index - 1) * 100,
                "scene": scene,
                "index": index,
            })
    return rows


def accessory_models(index: int) -> str:
    if index <= 3:
        return "VC-A1、VC-A2、VC-A3、VC-A4"
    if index <= 6:
        return "VC-A5、VC-A6、VC-A7、VC-A8"
    if index <= 8:
        return "VC-A9、VC-A10"
    if index <= 12:
        return "AP-A1、AP-A2、AP-A3、AP-A4"
    if index <= 15:
        return "AP-A5、AP-A6、AP-A7、AP-A8"
    return "HC-A1、HC-A2、HC-A3、HC-A4、HC-A5、HC-A6、HC-A7、HC-A8"


def product_document(product: dict) -> str:
    compatibility = accessory_models(product["index"]) if product["category"] == "耗材配件" else "本页不定义配件适配结论；兼容关系必须调用清居配件兼容 MCP 工具实时查询。"
    return f"""# {product['sku']} {product['name']} 商品资料

本文档的业务文档 ID 为 `{product['sku']}`，属于清居智能的静态商品知识。它用于解释商品分类、型号定位、使用场景与选购边界；文档中的示例价格仅用于演示，不作为下单、库存、订单或促销承诺的依据。

## 产品定位与静态档案

`{product['sku']}` 的展示型号为 `{product['model']}`，商品名称为“{product['name']}”，所属品类是“{product['category']}”。该 SKU 面向的核心使用场景是：{product['scene']}。在选购咨询中，应先确认用户的居住空间、主要困扰、使用频率以及是否已有同类设备，再说明该产品更适合承担的任务；不能仅按型号序号或价格高低给出结论。

该系列的演示目录价为 ¥{product['price']}，这个数字仅用于商品档案和问答示例，不代表实时售价。用户问“有没有货”“现在多少钱”“何时补货”时，答案必须改走 `qingju_inventory_query`，由模拟商城返回当前库存和售价。用户只提供模糊描述时，应引导补充 SKU、型号或具体的房间与清洁需求，避免把相邻型号的资料混到一起。

对比同类 SKU 时，建议围绕适用空间、清洁对象、耗材维护成本、收纳条件和用户愿意投入的操作时间展开。对于儿童、宠物、过敏、孕期或特殊健康状况，系统只能给出一般使用建议，不能给出医疗判断。产品知识库的职责是提供可追溯的静态事实，无法覆盖临时促销、库存波动和用户订单状态。

## 使用、维护与兼容边界

首次使用前，应核对外观、配件和说明书，确认电源、存放位置与通风条件符合产品手册要求。无线清洁类产品应优先关注尘杯、滤网、刷头和缝隙部件的清洁状态；空气护理类产品应关注进出风口、滤网状态和摆放空间；个护美发类产品应在干燥、稳定的环境中使用并避免线缆缠绕。具体步骤、维护周期和异常处理需要继续检索使用手册与 FAQ，商品页不能替代操作手册。

如果用户咨询配件，必须同时确认主机型号和配件 SKU。当前静态目录对本 SKU 的演示适配范围是：{compatibility}。这段信息仅帮助理解商品分类和提问方式，最终兼容结论以 `qingju_accessory_compatibility` MCP 返回的数据为准，因为配件适配关系属于模拟商城维护的实时业务数据。不得根据名称相似、外观相似或系列编号连续就推断两个产品一定兼容。

清居智能的知识库将“商品介绍”与“实时服务”分开处理：静态规格、维护原则和服务条款走 RAG；库存、订单、物流和精确兼容关系走 MCP。这样可避免向量检索命中旧文档后把过期库存或错误适配关系直接输出给用户，也便于在评测中分别观察检索质量和工具调用质量。

## 服务说明与回答约束

保修、退换货、配送和发票问题应继续引用服务政策库，而不是根据商品名称自行承诺。尤其是到货破损、质量故障、拆封状态、配送地址变更等问题，需要按政策资料说明申请条件、所需凭证和处理路径；订单是否已受理、是否已发货、是否进入售后，则必须调用订单或物流 MCP 工具查询。

当检索资料无法支持某个参数、服务时效或兼容结论时，应明确说明“当前资料未覆盖或需要实时查询”，并提出用户下一步可提供的信息。禁止把演示资料描述为真实商城的生产数据，禁止虚构库存数量、承运商、保修年限或活动权益。这样的边界也是清居评测集检查 faithfulness、answer_correctness 和 MCP 路由准确率的依据。

评测时，针对 `{product['sku']}` 的问题应优先命中本文档；若问题同时涉及实时库存或配件适配，静态商品文档只能作为补充上下文，最终事实应以工具返回结果为准。文档版本：2026-07-13，适用范围：清居智能模拟商城演示数据。

在知识库建设阶段，本文档与其他 SKU 文档保持相同的字段顺序：先给出 SKU、型号、品类与场景，再说明使用维护、兼容和服务边界。这样做不是为了堆砌描述，而是为了让“查型号参数”“选购比较”“配件是否兼容”三类问题能检索到不同的证据段落。若只把一行商品名、一个价格和一句卖点放进文档，模型虽然可能命中 SKU，却难以获得支撑完整回答的上下文，也无法通过 context_recall 的评测。

当用户的问法同时包含多个目标，例如“QJ-VC-008 适不适合宠物家庭、现在有货吗、能不能搭配某个耗材”，系统应拆分为静态选购、实时库存和实时兼容三个部分。第一部分引用本文档或选购指南，后两部分调用 MCP；最终回答要明确哪一段来自资料、哪一段来自实时工具。这个设计让清居场景能够检验多意图识别、混合证据拼装和回答忠实度，而不是仅测试单一的向量召回。

在实际评测中，SKU 编码会被保留为业务文档 ID，而不会使用上传后生成的数据库主键。这样可以让评测集准确标注“问 QJ-VC-008 时应召回哪篇资料”，并在后续更换知识库、重建向量或重新上传文档后保持稳定。对于型号别名、口语化说法和缺少连字符的 SKU 写法，应通过查询改写或文档中的别名字段提升召回；但改写后仍应保留原始型号，防止把 VC-A2、VC-A8 等相邻型号误判为同一商品。

如果回答无法在本文档、手册、政策或工具结果中找到依据，正确行为是明确说明信息不足并建议补充型号、订单号或故障现象。清居平台的目标是让每个事实都能追溯到知识证据或实时工具结果，而不是追求看似完整但不可验证的回答。

因此，本文档既是商品问答的知识来源，也是回归评测中的固定锚点。每次调整分块、检索、重排或提示词后，都应使用同一批 SKU 问题重新评测，确认命中率提升没有以幻觉、跑题或更长的首字延迟为代价。

该规则适用于所有清居 SKU 文档，并用于保证评测结果可重复、可解释和可追溯。

请以版本日期为准。

维护人员更新商品资料时，应同步检查型号、SKU、政策引用与评测样本是否仍一致，避免文档版本漂移造成检索命中正确但回答事实过期。
"""


def reference_document(doc_id: str, title: str, category: str, section_one: str, section_two: str, section_three: str) -> str:
    return f"""# {doc_id} {title}

本文档属于清居智能 {category}，业务文档 ID 为 `{doc_id}`。它服务于模拟商城的 RAG 评测，不包含真实消费者、真实订单或真实线上商城数据；涉及订单、物流、库存和精确配件兼容关系时，应调用对应 MCP 工具获取实时结果。

## {title}：适用范围与核心规则

{section_one}

为了让检索结果可解释，回答应先给出与用户问题直接相关的结论，再说明必要前提和下一步操作。不要把示例场景外推为所有型号或所有用户都适用的规则，也不要把静态政策资料当作实时订单状态。用户没有提供 SKU、型号、订单号或故障现象时，应先澄清，而不是猜测。

## {title}：操作与风险控制

{section_two}

清居知识库把商品、手册、政策和 FAQ 分成独立集合，是为了让意图树先缩小检索范围，再通过重排把最相关的证据放到前面。回答中若需要引用多个资料，应保持结论与资料边界一致：手册解释怎么做，政策解释能否办理，FAQ 解释先如何排查，实时工具回答当前业务状态。

## {title}：服务边界与评测要点

{section_three}

评测样本将以 `{doc_id}` 作为期望文档 ID，检查 Hit@K、Recall@K、MRR 和 RAGAS 上下文指标。若资料缺少关键事实，应先补充文档或修正切片，再调整提示词；不能用模型臆测掩盖知识库缺失。文档版本：2026-07-13，适用范围：清居智能模拟商城演示数据。

文档按“规则说明、操作风险、服务边界”三段组织，目的是让结构化分块器在保留段落语义的同时产生多个可独立检索的 chunk。每个 chunk 都应包含足够的主语、条件和结论，避免出现只有“可以”“不可以”而没有对象的碎片。这样在用户问保修、故障或使用步骤时，重排器能把包含完整条件的证据放在前面，模型也更容易避免把相邻段落的规则拼错。

清居评测会保存每个问题命中的文档和 chunk，并把最终回答交给 RAGAS 进行忠实度、相关性、正确性、上下文精度和上下文覆盖评估。发现低分时，应先按意图和样本回看证据：资料未命中先修知识与召回，资料已命中但回答扩写过度再调提示词；不要只盯整体平均分，也不要用单条看起来正确的回答证明整个链路稳定。

资料维护时，应为每个规则写清适用对象、前置条件、操作动作、例外情况和升级路径。比如“检查滤网”必须说明在断电后进行、哪些现象可以继续观察、哪些现象必须停止使用；“查询订单”必须说明订单号是工具参数而不是静态资料关键词。这样能降低 RAG 在跨文档拼接时丢失条件的概率，也能让人工复核知道模型到底遗漏了什么。对于表述存在歧义的规则，优先补充明确文字，不要依赖模型通过常识自行补全。

同一类问题往往会同时命中商品、手册、政策和 FAQ。系统应根据意图先确定主证据：选购看商品和指南，操作看手册，能否办理看政策，设备异常先看 FAQ；主证据之外的材料只能作为补充。若问题里包含当前库存、已发货、物流节点、订单售后状态或精确兼容关系，则属于实时业务事实，必须调用 MCP。混合问题可以组合 RAG 与 MCP 的结果，但最终答案必须清楚地区分规则说明和当前查询结果。

这些资料既用于回答，也用于构建可回归的评测集。每次更新分块规则、向量模型、重排模型、意图阈值或 Prompt 后，应使用相同的业务文档 ID 做 A/B Diff：检索指标提升但 faithfulness 下降，说明不能直接合入；首字延迟显著增加，也要继续定位意图、检索、工具和模型各阶段耗时。通过固定的资料版本和失败样本回灌，才能证明优化是可重复的，而不是偶然命中。

清居模拟商城的产品、订单和服务规则会持续扩展，因此文档不应把当前示例覆盖范围写成永久承诺。新增 SKU 时，应同时创建静态商品资料、需要时新增手册或 FAQ、为评测集补充该 SKU 的标准问题，并检查其是否需要新的 MCP 参数或兼容规则。删除或替换资料时，也要同步更新期望文档 ID，防止评测因引用失效文件得到虚假的低分。这个变更纪律能让知识库、工具数据和评测资产始终保持同一套业务语义。

如果用户的问题不在资料定义的范围内，系统应说明当前可处理的清居商品、使用、售后和实时查询能力，并在必要时请求澄清。拒答不是失败：对于没有证据、没有足够参数或超出业务范围的问题，可靠地收敛边界比生成看似合理的答案更重要。

为了避免单一切片只保留摘要而遗漏条件，本资料还补充一条具体的检索约束：当用户问题包含型号、SKU、订单号或配件编码时，系统要保留这些实体并优先在对应资料中匹配；当用户问题只描述现象或场景时，才使用同义词、口语化表达和上位类目进行扩展召回。扩展召回得到多个相近资料后，必须通过重排和元数据过滤选出主证据，不能因为候选数量多就把所有内容都交给模型。这样能在覆盖率与噪声之间保持平衡。

对于需要多个证据才能回答的题目，回答结构应先给出用户可执行的结论，再分别列出资料支持的条件和实时工具查询到的状态。例如配送政策可以说明如何处理地址变更，而订单工具才说明某订单是否已经发货；配件资料可以解释为什么不能按外观判断，兼容工具才给出某个型号与配件 SKU 的最终结论。把这两类信息混在一起，会使用户误认为静态规则能证明当前状态，也会拉低评测中的答案正确性。
"""


def write_document(path: Path, content: str, catalog: dict, kb_key: str) -> None:
    if len(content) < 2100:
        raise ValueError(f"document is too short for multi-chunk ingestion: {path.name} ({len(content)} chars)")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    catalog[path.stem] = {
        "kb_key": kb_key,
        "category": path.parent.name,
        "rel_path": path.relative_to(ROOT).as_posix(),
    }


def build_documents() -> dict:
    catalog = {}
    for product in product_rows():
        path = KB_ROOT / "01_product" / "sku" / f"{product['sku']}.md"
        write_document(path, product_document(product), catalog, "product")

    references = [
        ("01_product/guide/GUIDE_QJ_VAC_001.md", "无线清洁机选购指南", "商品知识库",
         "选择无线清洁机时，先确认地面材质、毛发或碎屑类型、需要清洁的高频区域，以及是否需要频繁收纳。面积较小且以日常浮尘为主时，重点看轻便与维护流程；面积较大、地毯或缝隙较多时，重点确认刷头、续航安排和耗材维护是否能匹配使用频率。不要把单次清洁感受等同于所有家庭环境的结果。",
         "用户描述“吸力不够”前，应先澄清滤网、尘杯、刷头缠绕和电量情况。首次使用、充电、滤网清洁等具体动作要遵循无线清洁机手册；若设备出现持续异常、异味或无法启动，应转入故障 FAQ 或人工售后流程。商品推荐可以给出比较维度，但不能替代安全排障。",
         "SKU 的当前库存和售价由 `qingju_inventory_query` 返回，不能根据商品目录做推测。配件兼容需要同时给出主机型号和配件 SKU，并使用 `qingju_accessory_compatibility` 查询。选购建议应说明依据是静态资料，避免承诺某个用户一定获得相同效果。"),
        ("01_product/guide/GUIDE_QJ_AIR_001.md", "空气护理机选购指南", "商品知识库",
         "空气护理机的选购要结合房间面积、通风条件、使用人群和滤网维护意愿。系统应先问清用户主要是关注日常空气循环、卧室闷热感还是滤网维护，再推荐相应系列。空气护理设备是居家用品，不应被描述为医疗设备，也不应承诺治疗、消除过敏或替代专业健康建议。",
         "摆放时应预留进出风空间，避免紧贴墙面、窗帘或覆盖物。用户询问异味、风量下降或维护提示时，可根据 FAQ 引导检查滤网、进风口和放置环境；若有异响、烧焦味或反复故障，应建议停止使用并联系售后。具体滤网周期以手册和实际使用环境为准。",
         "空气护理机的价格与库存会变化，实时问题应调用库存 MCP。静态商品资料只能描述型号、分类和维护原则，不能输出当前库存数或活动价。评测时要区分“适用场景”知识题与“还有没有货”工具题，避免意图树把两者混为一谈。"),
        ("01_product/guide/GUIDE_QJ_CARE_001.md", "个护造型器选购指南", "商品知识库",
         "个护造型器适合按发质、日常造型频率、收纳空间和用户对操作复杂度的接受度进行选择。推荐时应说明不同型号只代表模拟目录中的系列区分，不能虚构温度档位、护理功效或专业沙龙效果。对于用户提出的敏感头皮、疾病或孕期问题，应避免给出医疗结论。",
         "使用前应保持设备和手部干燥，检查电源线和配件状态；使用后待设备冷却再收纳。出现过热、异常气味、无法启动或线缆破损时，应停止继续尝试并参考 FAQ 或售后政策。日常清洁只应在断电、冷却状态下进行，不能用浸水方式处理主机。",
         "个护产品的订单、物流与实时库存均不在本指南范围内。用户咨询某个配件是否能用于 HC-A 系列时，应使用配件兼容 MCP 查询；政策问题引用政策库。这样可以避免把静态选购材料误当成实时业务凭据。"),
        ("01_product/guide/GUIDE_QJ_COMPARE_001.md", "清居多品类场景对比指南", "商品知识库",
         "无线清洁、空气护理和个护造型解决的是不同的居家需求：前者偏向地面与局部卫生维护，第二类偏向室内空气循环和滤网维护，第三类偏向个人日常造型。比较时不要简单按价格排序，而应回到用户的主要困扰、使用空间、维护成本和可接受的操作频率。",
         "复杂问题可以拆为多个子问题，例如“家里有宠物又觉得卧室空气闷”应分别检索清洁与空气护理资料，再由回答阶段合并建议。拆分后仍要避免把两个产品的功能混写；每个结论都应能在其对应资料中找到支持。涉及设备故障时，应优先进入 FAQ，而不是继续做选购推荐。",
         "本指南不提供实时套餐、库存或订单信息。若用户问具体 SKU 的可售数量、订单配送或配件适配，意图树应该路由到 MCP 叶子。该区分会用于评测过度检索率和 MCP 路由准确率。"),
        ("02_manual/device/MANUAL_QJ_VAC_001.md", "无线清洁机使用与维护手册", "使用手册库",
         "首次使用无线清洁机前，应确认主机、充电组件、尘杯、滤网和刷头外观完好，并在干燥、平稳的位置完成充电。清洁时先根据地面和缝隙选择合适配件，避免在有大量液体、尖锐物或未固定线缆的区域直接操作。手册只提供一般家庭维护原则，不能覆盖所有特殊环境。",
         "吸力下降时，建议按尘杯是否已满、滤网是否积尘、刷头是否缠绕、电量是否不足的顺序检查。清洁滤网或刷头前先关闭设备并按说明拆卸，待部件干燥后再装回。若设备持续发热、出现焦味、异响或无法启动，应停止使用，避免反复强行启动造成风险。",
         "本手册不定义库存、订单或售后处理结果。配件能否适配指定 VC-A 型号需要调用兼容工具；保修和退换条件应引用政策库。回答时可先给排查步骤，再告诉用户什么情况应停止自检并申请售后。"),
        ("02_manual/device/MANUAL_QJ_AIR_001.md", "空气护理机使用与维护手册", "使用手册库",
         "空气护理机应放在稳定、通风且进出风口无遮挡的位置，避免紧贴墙面、厚窗帘或高湿区域。首次使用前核对滤网和外壳状态，接通电源后观察运行是否平稳。用户希望改善房间体感时，应同时考虑开窗、清洁和设备维护等因素，不能把设备效果表述为医疗或绝对承诺。",
         "出现风量变小、维护提示或异味时，先断电检查滤网安装、进风口积尘和周边遮挡。不要使用腐蚀性清洁剂处理机身，也不要在未断电状态下拆卸。若有持续异响、异常发热或烟味，应立即停止使用并转售后处理；故障 FAQ 只提供初步排查，不替代维修。",
         "滤网型号、具体更换时间和当前耗材库存应结合设备型号与实时工具结果确认。静态手册负责说明检查顺序和安全边界，MCP 负责回答具体 SKU 是否有库存或配件是否兼容。"),
        ("02_manual/device/MANUAL_QJ_CARE_001.md", "个护造型器使用与维护手册", "使用手册库",
         "个护造型器使用前应确认机身、线缆和配件干燥完好，选择稳定电源并避免在潮湿环境操作。开始造型时建议从低强度、短时使用开始，观察设备状态和个人舒适度。不要将一般家庭产品说明延伸为对特定发质、头皮疾病或医疗护理的结论。",
         "使用结束后关闭电源，等待机身冷却后再清理表面与收纳。发现过热、异味、异常噪声、外壳损坏或线缆破损时，停止继续使用，也不要自行拆开主机。用户仅描述“不能用”时，需要补充型号、现象、是否通电和是否出现提示信息，才能进入更准确的 FAQ 排查。",
         "此手册不包含当前售价、库存数量、订单状态或延保是否已生效。上述问题要走对应实时工具或服务政策库。配件适配结论不能依据外观猜测，必须使用兼容 MCP 工具确认。"),
        ("02_manual/app/MANUAL_QJ_APP_001.md", "清居智能 App 连接与设备管理手册", "使用手册库",
         "设备连接 App 前，应确认手机网络正常、设备处于可连接状态，并按设备类别完成基础供电与摆放。若首次连接失败，先检查网络名称、权限设置、设备距离和重新进入配网状态的步骤。App 手册只解释一般连接流程，不能承诺所有路由器、所有网络环境都能一次连接成功。",
         "连接成功后，用户可在 App 中查看设备基础信息、维护提醒和已绑定设备。出现设备离线、页面无响应或重复配网时，应先退出并重新进入连接流程，必要时根据故障 FAQ 收集型号、现象和已尝试步骤。不要让用户在不明来源页面输入订单号、验证码或个人敏感信息。",
         "订单物流、库存和售后进度不以 App 静态说明为准，仍应调用模拟商城 MCP 查询。地图天气能力也由独立高德工具提供；App 手册不应伪造天气、地址或路线数据。"),
        ("03_policy/service/POLICY_QJ_WARRANTY_001.md", "清居售后保修政策", "服务政策库",
         "清居智能模拟商城的保修咨询应先核对商品类别、购买凭证、故障表现和是否属于正常使用范围。系统可说明一般申请路径：保留订单或凭证、描述问题、上传必要材料并等待售后审核；不能在没有具体政策证据时承诺固定年限、免费范围或必然受理结果。",
         "对于持续异响、发热、焦味、外壳损坏等安全风险，应建议用户停止使用，避免继续通电测试。售后政策用于解释申请条件和风险边界，不替代维修诊断。已经进入售后、退款或换货流程的真实状态属于实时数据，应由订单 MCP 查询而不是根据政策文本推断。",
         "评测中，保修类问题要求回答忠于政策上下文，避免补充不存在的延保年限或赔付承诺。若用户问礼盒权益是否已绑定到某订单，应澄清订单号并转到实时订单查询，而不是仅依据礼盒商品介绍作答。"),
        ("03_policy/service/POLICY_QJ_RETURN_001.md", "清居退换货与配送政策", "服务政策库",
         "退换货咨询应先确认商品签收时间、包装与配件状态、用户提出的原因以及是否存在安全或质量问题。系统可以说明一般流程：提交申请、等待审核、按指引寄回或安排处理；不得把演示资料解释为无条件退款、无时限换货或自动承担全部运费。具体结果以订单售后状态和审核信息为准。",
         "配送问题要区分“订单尚未发货”“已发货但运输中”“已签收”和“部分发货”。政策库负责说明用户应如何查询、修改信息或联系服务，不直接给出当前承运商、运单号和预计送达。用户提供订单号后，应使用 `qingju_logistics_query` 获取模拟商城的实时物流节点。",
         "回答中应明确政策资料与实时业务数据的边界。因为物流状态可能变化，任何静态文档都不应代替工具返回；这也是评测错误路由和答案忠诚度时的重点检查项。"),
        ("03_policy/service/POLICY_QJ_INVOICE_001.md", "清居发票与礼盒服务说明", "服务政策库",
         "用户询问发票时，应说明需要提供的开票信息、订单关联方式和申请入口，并提醒核对抬头、税号和联系方式。系统不能生成真实发票，也不能从静态资料推断某个订单是否已开票。订单级状态需要通过实时订单服务或人工渠道确认。",
         "延保礼盒和服务权益属于模拟商品目录的一部分，用户应先确认礼盒 SKU、订单号和服务说明。若资料未覆盖某类权益的生效时间、转赠条件或退款规则，应如实说明当前政策未覆盖，不要把营销措辞扩展为合同承诺。",
         "发票、礼盒和订单问题常同时涉及政策和实时数据。回答应先用政策解释规则，再在用户提供订单号后调用订单工具查询状态；两类证据不能互相替代。"),
        ("03_policy/service/POLICY_QJ_PRIVACY_001.md", "清居订单信息与隐私说明", "服务政策库",
         "查询模拟订单时，用户应提供订单号；若同时提供手机号后四位，系统可用于校验。回答中不应展示或猜测完整手机号、地址、支付账号等不必要信息。即使是模拟数据，也要按最小必要原则处理订单标识和个人信息。",
         "用户提出物流、售后或开票问题时，模型应提取完成任务所需的最少参数，并把工具返回结果限制在问题范围内。若订单号不完整、格式不清或与校验信息不一致，应请求用户核对，而不是根据相近订单号自动匹配。",
         "本政策用于约束模型输出与参数提取方式，不提供任何订单的实际状态。订单状态、物流节点、库存和兼容结论均来自相应 MCP 工具，静态政策资料不能作为实时事实来源。"),
        ("04_faq/trouble/FAQ_QJ_VAC_001.md", "无线清洁机常见问题排查", "故障 FAQ 库",
         "当用户反馈吸力下降、刷头不转或续航异常时，先确认设备型号、发生时间、是否有提示信息，以及尘杯、滤网、刷头和电量状态。可按先易后难的顺序排查：清理可见堵塞，检查刷头缠绕，确认滤网干燥并正确安装，再完成一次规范充电。不要在设备异常发热时继续反复启动。",
         "若清洁后问题仍存在，或出现烧焦味、持续异响、外壳破损、无法充电等情况，应停止使用并联系售后。FAQ 的作用是帮助用户完成安全的初步检查，不应给出拆机维修、电路维修或绕过保护机制的建议。涉及保修资格与售后进度时，分别检索政策库或查询订单工具。",
         "回答应明确哪些是用户可自行检查的项目，哪些必须停止操作并转人工服务。评价 RAG 效果时，故障题既要看是否召回本文档，也要看模型是否忠实保留安全边界，避免为了给出“解决方案”而编造具体故障原因。"),
        ("04_faq/trouble/FAQ_QJ_AIR_001.md", "空气护理机常见问题排查", "故障 FAQ 库",
         "用户反馈风量变小、异味或维护提示时，应先确认设备型号、滤网状态、进出风口是否被遮挡以及设备所处环境。可以建议断电后检查外观、清理可见灰尘并按照手册重新安装滤网；不要建议使用腐蚀性液体、浸水冲洗主机或在通电状态下拆卸。",
         "若出现明显异响、异常发热、烟味或反复报警，应立即停止使用并申请售后。对于空气质量、过敏和疾病问题，系统只能提供一般居家维护建议，不能作医疗诊断或承诺治疗效果。滤芯库存和精确型号兼容关系属于实时信息，需使用库存或兼容工具查询。",
         "排查回答应先说明最安全的检查路径，再说明何时升级到售后。不要因为检索到相似型号的资料就把它当作当前设备的确定结论；必要时先追问型号和现象，降低错答风险。"),
        ("04_faq/trouble/FAQ_QJ_CARE_001.md", "个护造型器常见问题排查", "故障 FAQ 库",
         "个护造型器无法启动、温度异常或出现异味时，应先停止使用并检查电源、线缆、插座和设备冷却状态。用户可在断电且设备冷却后检查表面和配件是否有明显破损，但不应自行拆开主机或继续长时间通电测试。安全风险优先于完成一次问答。",
         "若问题在更换安全电源环境后仍存在，或发现线缆破损、外壳裂纹、持续高温、烟味等情况，应建议尽快申请售后。FAQ 不提供维修电路、改装或绕过温控保护的指引。需要了解保修条件时，引用售后政策；需要确认某订单是否在处理售后时，调用订单 MCP。",
         "该文档用于区分一般维护咨询与高风险故障报告。模型应该避免把没有证据的原因当成结论，也不能以“可能没问题”为理由淡化明确的安全信号。"),
        ("04_faq/trouble/FAQ_QJ_APP_001.md", "清居智能 App 连接问题排查", "故障 FAQ 库",
         "App 无法发现设备、设备反复离线或配网失败时，先确认手机网络、App 权限、设备供电状态、距离和网络环境。用户应按手册重新进入连接流程，并记录设备型号、报错页面和已尝试步骤。不要要求用户在对话中提供验证码、完整手机号、密码或其他敏感凭据。",
         "若多次规范操作仍失败，可建议用户通过售后渠道提交设备型号和故障描述。网络配置因家庭路由器、权限和环境不同而变化，系统不应保证一次即可解决，也不要将模拟文档描述为官方实时服务通告。订单与物流不属于 App 故障排查范围，应通过相应 MCP 工具查询。",
         "评测时，连接类问题应命中本 FAQ 或 App 手册；如果模型把它误路由到库存、订单或天气工具，说明意图树的边界示例需要补充。"),
    ]
    for rel_path, title, category, first, second, third in references:
        write_document(KB_ROOT / rel_path, reference_document(Path(rel_path).stem, title, category, first, second, third), catalog,
                       {"01_product": "product", "02_manual": "manual", "03_policy": "policy", "04_faq": "faq"}[Path(rel_path).parts[0]])
    return catalog


def static_samples() -> list[dict]:
    samples = [
        ("QJ_PRODUCT_RECOMMEND", "80 平两居室有宠物毛发，清居无线清洁机怎么选？", ["GUIDE_QJ_VAC_001", "QJ-VC-002"]),
        ("QJ_PRODUCT_SPEC", "QJ-VC-008 对应的型号、适用场景和咨询边界是什么？", ["QJ-VC-008"]),
        ("QJ_PRODUCT_COMPARE", "VC-A2 和 VC-A8 应从哪些维度比较？", ["GUIDE_QJ_COMPARE_001", "QJ-VC-002", "QJ-VC-008"]),
        ("QJ_USAGE_GUIDE", "无线清洁机吸力下降时，我应该先检查什么？", ["MANUAL_QJ_VAC_001"]),
        ("QJ_USAGE_GUIDE", "空气护理机提示维护时，能直接清洗主机吗？", ["MANUAL_QJ_AIR_001"]),
        ("QJ_FAQ_TROUBLESHOOT", "清洁机有焦味还可以继续启动测试吗？", ["FAQ_QJ_VAC_001"]),
        ("QJ_AFTERSALES_POLICY", "清居设备发生故障后如何申请售后？", ["POLICY_QJ_WARRANTY_001"]),
        ("QJ_AFTERSALES_POLICY", "订单发货后如何查询物流，不想要了怎么办？", ["POLICY_QJ_RETURN_001"]),
    ]
    rows = []
    for index, (intent, query, docs) in enumerate(samples, start=1):
        rows.append({
            "query_id": f"QJ-KB-{index:03d}", "query": query, "intent_l1": "QINGJU_KB", "intent_l2": intent,
            "difficulty": ["easy", "medium", "hard"][(index - 1) % 3], "requires_rag": True,
            "expected_answer_type": "knowledge_answer", "expected_doc_ids": docs,
            "ground_truth": "回答必须基于清居静态知识文档，并在资料不足时说明边界。",
            "eval_metrics": ["intent_accuracy", "hit@5", "recall@5", "mrr@10"],
        })
    return rows


def mcp_samples() -> list[dict]:
    samples = [
        ("QJ_ORDER_STATUS", "订单 QJ20260713008 现在是什么状态？", "qingju_order_query"),
        ("QJ_ORDER_LOGISTICS", "QJ20260713008 的物流到哪里了？", "qingju_logistics_query"),
        ("QJ_INVENTORY", "QJ-VC-008 现在还有库存吗？", "qingju_inventory_query"),
        ("QJ_ACCESSORY_COMPATIBILITY", "VC-A2 能使用 QJ-ACC-002 吗？", "qingju_accessory_compatibility"),
        ("QJ_AMAP_GEOCODE", "杭州东站的地址坐标是什么？", "amap_geocode"),
        ("QJ_AMAP_ROUTE", "从杭州东站开车到西湖景区需要多久？", "amap_driving_route"),
        ("QJ_AMAP_WEATHER", "杭州西湖区现在天气怎么样？", "amap_weather_query"),
    ]
    rows = []
    for index, (intent, query, tool_id) in enumerate(samples, start=1):
        rows.append({
            "query_id": f"QJ-MCP-{index:03d}", "query": query, "intent_l1": "QINGJU_REALTIME", "intent_l2": intent,
            "difficulty": "medium", "requires_rag": False, "expected_answer_type": "mcp_answer",
            "expected_doc_ids": [], "expected_mcp_tool_id": tool_id,
            "ground_truth": f"必须路由到 MCP 工具 {tool_id}，不得用静态资料编造实时结果。",
            "eval_metrics": ["intent_accuracy", "mcp_route_accuracy", "mcp_tool_success"],
        })
    return rows


def build_datasets() -> None:
    seed_rows = static_samples() + mcp_samples()
    full_rows = []
    for repeat in range(1, 5):
        for row in seed_rows:
            copy = dict(row)
            copy["query_id"] = f"{row['query_id']}-R{repeat}"
            full_rows.append(copy)
    DATASET_ROOT.mkdir(parents=True, exist_ok=True)
    for path, rows in [
        (DATASET_ROOT / "eval_set_qingju_smoke.jsonl", seed_rows),
        (DATASET_ROOT / "eval_set_qingju_all.jsonl", full_rows),
    ]:
        with path.open("w", encoding="utf-8", newline="\n") as file:
            for row in rows:
                file.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_readme(catalog: dict) -> None:
    counts = {key: sum(meta["kb_key"] == key for meta in catalog.values()) for key in KB_SPECS}
    text = f"""# 清居智能 RAG + MCP 评测资产

这是独立于茶叶场景的清居智能模拟商城评测工作区。静态资料走 RAG，订单、物流、库存、配件兼容与高德能力走 MCP；不要把模拟数据描述为真实商城生产数据。

## 资产规模

- 商品 SKU 文档：50 篇，编码与 `mcp-server` 中的模拟商城保持一致。
- 指南、手册、政策与 FAQ：16 篇。
- 知识库分布：{counts}。
- 每篇文档至少 2100 个字符；配合 `upload_docs.py` 的 `targetChars=1400`、`maxChars=1800`，设计目标为 2 至 3 个切片。实际切片数以 Tika 和结构化切分结果为准。

## 初始化

先启动 `mcp-server`，再启动或重启 `bootstrap`，确认 Bootstrap 日志中已发现 MCP 工具。随后在仓库根目录执行：

```powershell
$env:RAG_EVAL_ROOT = (Resolve-Path "eval/rag/qingju").Path
$env:RAGENT_BASE_URL = "http://localhost:9090/api/ragent"
$env:RAGENT_USERNAME = "admin"
$env:RAGENT_PASSWORD = "admin"

python eval/rag/qingju/init/generate_qingju_assets.py
python eval/rag/init/create_kbs.py
python eval/rag/init/upload_docs.py --dry-run
python eval/rag/init/upload_docs.py --sleep 1
python eval/rag/init/build_intent_tree.py --dry-run
python eval/rag/init/build_intent_tree.py
python eval/rag/qingju/init/verify_chunks.py
python eval/rag/qingju/init/generate_qingju_eval_set.py
python eval/rag/run_eval.py --dataset eval/rag/qingju/datasets/eval_set_qingju_150.jsonl --out eval/rag/qingju/runs/qingju_150.jsonl
python eval/rag/qingju/score_qingju.py eval/rag/qingju/runs/qingju_150.jsonl
python eval/rag/qingju/generate_analysis_report.py eval/rag/qingju/runs/qingju_150_qingju_scores.json
```

`state/` 目录会保存该场景创建出的知识库、文档和意图树 ID。若要重新初始化清居场景，只能在确认该场景资源可删除后运行既有的 `reset_kbs.py`，并保持 `RAG_EVAL_ROOT` 指向本目录。

仅修改了本地文档内容、需要重新上传时，可执行 `python eval/rag/init/upload_docs.py --force`。该命令只会删除并重传当前 `RAG_EVAL_ROOT` 已记录的文档，不会重建意图树；执行后应再次运行 `verify_chunks.py`。

## 评测集

- `datasets/eval_set_qingju_smoke.jsonl`：15 条冒烟样本，覆盖静态 RAG 与 MCP 路由。
- `datasets/eval_set_qingju_150.jsonl`：150 条唯一问题；静态 RAG 60 条、MCP 实时查询 70 条、系统兜底 20 条，每个叶子意图 10 条。
- `runs/`：真实旁路评测录制结果；`reports/`：逐指标分析文档。它们是本地运行产物，不提交版本库。

当前通用 Runner 的 Hit@K、Recall@K、MRR 只针对 `requires_rag=true` 的静态知识题。清居专用评分脚本会利用 `/rag/eval` 的 `hasMcp`、本地意图 ID 映射和 `expected_mcp_tool_id` 计算 MCP 路由指标。RAGAS 的 faithfulness、answer_relevancy、answer_correctness、context_precision、context_recall 需要额外录制 `/rag/v3/chat` 的 SSE 答案并配置 Judge；不能用文档命中率替代。
"""
    (ROOT / "README.md").write_text(text, encoding="utf-8")


def main() -> None:
    catalog = build_documents()
    write_json(ROOT / "kb_specs.json", KB_SPECS)
    write_json(ROOT / "doc_catalog.json", catalog)
    write_json(ROOT / "intent_tree_spec.json", TREE_SPEC)
    build_datasets()
    write_readme(catalog)
    lengths = [len((ROOT / meta["rel_path"]).read_text(encoding="utf-8")) for meta in catalog.values()]
    (ROOT / "VALIDATION.md").write_text(
        f"# 清居智能知识库校验\n\n文档数：{len(catalog)}\n\n最短正文：{min(lengths)} 字符\n\n最长正文：{max(lengths)} 字符\n\n"
        "上传脚本使用 targetChars=1400、maxChars=1800；本文档按 2 至 3 个结构化切片设计，实际结果以服务端分块记录为准。\n",
        encoding="utf-8",
    )
    print(f"generated Qingju assets: {len(catalog)} documents, 15 smoke samples, 60 baseline samples")


if __name__ == "__main__":
    main()
