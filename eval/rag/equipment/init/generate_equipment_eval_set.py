from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET_PATH = ROOT / "datasets" / "eval_set_equipment_150.jsonl"


STATIC_CASES = {
    "EQ_MODEL_SPEC": [
        ("CNC-MT-1060 的行程、主轴转速和刀库配置分别是什么？", ["CNC-MT-1060-MODELS"]),
        ("ROB-IR-20 的额定负载、臂展和防护等级怎么理解？", ["ROB-IR-20-MODELS"]),
        ("CONV-AS-600 的分拣能力和传感器配置适合什么场景？", ["CONV-AS-600-MODELS"]),
        ("CNC-GR-630 的精度能力和适用产线是什么？", ["CNC-GR-630-MODELS"]),
        ("ROB-SC-04 用在精密装配时需要注意哪些参数边界？", ["ROB-SC-04-MODELS"]),
        ("CONV-VF-450 的额定载荷和变频器规格是什么？", ["CONV-VF-450-MODELS"]),
        ("CNC-LT-500 可以加工多大直径的轴类零件？", ["CNC-LT-500-MODELS"]),
        ("ROB-WL-12 的焊接能力和产线定位是什么？", ["ROB-WL-12-MODELS"]),
        ("CONV-VF-200 的速度范围与机加工物流线如何匹配？", ["CONV-VF-200-MODELS"]),
        ("CNC-MT-1270 的最大工件能力和适用的加工任务是什么？", ["CNC-MT-1270-MODELS"]),
    ],
    "EQ_MODEL_COMPARE": [
        ("CNC-MT-850 与 CNC-MT-1060 在行程和主轴能力上有什么差别？", ["CNC-MT-850-MODELS", "CNC-MT-1060-MODELS"]),
        ("CNC-MT-1270 和 CNC-LT-500 分别适合什么类型的零件加工？", ["CNC-MT-1270-MODELS", "CNC-LT-500-MODELS"]),
        ("ROB-IR-10 和 ROB-IR-20 的负载与适用产线怎样选择？", ["ROB-IR-10-MODELS", "ROB-IR-20-MODELS"]),
        ("ROB-WL-12 与 ROB-PAL-25 的能力边界为什么不能混用？", ["ROB-WL-12-MODELS", "ROB-PAL-25-MODELS"]),
        ("ROB-SC-04 与六轴机器人在精密装配中的定位差异是什么？", ["ROB-SC-04-MODELS", "ROB-IR-10-MODELS"]),
        ("CONV-VF-200 和 CONV-VF-300 的线体宽度、速度和部署位置有什么区别？", ["CONV-VF-200-MODELS", "CONV-VF-300-MODELS"]),
        ("CONV-VF-450 与 CONV-AS-600 在载荷和分拣能力上如何比较？", ["CONV-VF-450-MODELS", "CONV-AS-600-MODELS"]),
        ("机加工车间为什么不能直接用装配车间的 CONV-AS-800 代替 CONV-VF-450？", ["CONV-AS-800-MODELS", "CONV-VF-450-MODELS"]),
        ("CNC-GR-630 与 CNC-MT-850 的精度目标和加工方式差异是什么？", ["CNC-GR-630-MODELS", "CNC-MT-850-MODELS"]),
        ("ROB-IR-20 与 ROB-PAL-25 的负载接近时，还要比较哪些产线条件？", ["ROB-IR-20-MODELS", "ROB-PAL-25-MODELS"]),
    ],
    "EQ_FAULT_DIAGNOSIS": [
        ("CNC-203 伺服跟随误差超限时，应该如何分级排查？", ["CNC-MT-1060-FAULTS"]),
        ("CNC-101 主轴温升异常有哪些可能原因，为什么要先停机？", ["CNC-MT-850-FAULTS"]),
        ("CNC-305 液压压力不足时，夹紧加工能否继续？", ["CNC-MT-1270-FAULTS"]),
        ("CNC-407 冷却液浓度异常的安全处理步骤是什么？", ["CNC-GR-630-FAULTS"]),
        ("ROB-201 伺服驱动过载报警为什么要确认防护区人员？", ["ROB-IR-10-FAULTS"]),
        ("ROB-302 碰撞检测触发后，为什么不能强制复位？", ["ROB-IR-20-FAULTS"]),
        ("ROB-404 焊接电流不稳定时，先检查哪些非拆机问题？", ["ROB-WL-12-FAULTS"]),
        ("ROB-503 夹爪真空不足会带来什么风险，应怎么处置？", ["ROB-PAL-25-FAULTS"]),
        ("CONV-101 变频器过流时，为什么要先断开上游供料？", ["CONV-VF-200-FAULTS"]),
        ("CONV-204 光电传感器信号丢失时，单件测试前要完成什么？", ["CONV-VF-300-FAULTS"]),
    ],
    "EQ_INSPECTION_SOP": [
        ("CNC-MT-850 班前点检应该按哪些层次执行？", ["CNC-MT-850-MAINTENANCE"]),
        ("CNC-MT-1060 开机前为什么要先低速空载确认？", ["CNC-MT-1060-MAINTENANCE"]),
        ("CNC-MT-1270 保养液压系统时的核心点检要求是什么？", ["CNC-MT-1270-MAINTENANCE"]),
        ("ROB-IR-10 班前怎么检查安全围栏和零位？", ["ROB-IR-10-MAINTENANCE"]),
        ("ROB-IR-20 碰撞维修后如何完成保养和验证？", ["ROB-IR-20-MAINTENANCE"]),
        ("ROB-WL-12 的送丝机构和接地回路点检怎么做？", ["ROB-WL-12-MAINTENANCE"]),
        ("CONV-VF-200 皮带输送线班前要检查哪些传动和传感部件？", ["CONV-VF-200-MAINTENANCE"]),
        ("CONV-VF-300 发生跑偏前，保养中应该记录哪些信息？", ["CONV-VF-300-MAINTENANCE"]),
        ("CONV-AS-600 分拣线保养后为什么要做单件和连续验证？", ["CONV-AS-600-MAINTENANCE"]),
        ("CNC-LT-500 自动生产前，怎样确认夹具和安全条件？", ["CNC-LT-500-MAINTENANCE"]),
    ],
    "EQ_REPAIR_CASE": [
        ("CNC-MT-1060 出现伺服报警的维修案例中，验证恢复效果分几步？", ["CNC-MT-1060-REPAIR"]),
        ("CNC-MT-850 更换主轴相关备件前需要核对什么？", ["CNC-MT-850-REPAIR"]),
        ("CNC-MT-1270 维修液压异常时，工单闭环必须记录哪些字段？", ["CNC-MT-1270-REPAIR"]),
        ("ROB-IR-20 碰撞后更换线缆，为什么不能使用未确认的替代件？", ["ROB-IR-20-REPAIR"]),
        ("ROB-WL-12 焊接异常处理完后应该如何做试运行？", ["ROB-WL-12-REPAIR"]),
        ("ROB-PAL-25 的减速机备件领用时要验证哪些适配关系？", ["ROB-PAL-25-REPAIR"]),
        ("CONV-VF-300 更换变频器前，为什么要查询实时库存和库位？", ["CONV-VF-300-REPAIR"]),
        ("CONV-AS-600 的传感器维修案例如何保留质量证据？", ["CONV-AS-600-REPAIR"]),
        ("CNC-GR-630 发生冷却液问题后，维修记录和工艺记录怎样关联？", ["CNC-GR-630-REPAIR"]),
        ("CONV-VF-450 传动异常后，哪些信息不能只靠口头交接？", ["CONV-VF-450-REPAIR"]),
    ],
    "EQ_OPERATION_SAFETY": [
        ("机器人碰撞报警时，谁可以进入防护区处理？", ["ROB-IR-20-FAULTS"]),
        ("数控机床主轴温升异常为什么不能先继续跑完这一批工件？", ["CNC-MT-850-FAULTS"]),
        ("输送线过流后，恢复试车前必须排除哪些风险？", ["CONV-VF-200-FAULTS"]),
        ("液压压力不足时，为什么不能继续夹紧加工？", ["CNC-MT-1270-FAULTS"]),
        ("焊接机器人电流不稳时，什么行为属于不安全操作？", ["ROB-WL-12-FAULTS"]),
        ("光电传感器故障后为什么不能直接恢复自动分拣？", ["CONV-AS-600-FAULTS"]),
        ("码垛机器人真空不足时，如何防止工件坠落？", ["ROB-PAL-25-FAULTS"]),
        ("数控车床出现伺服误差后，为什么要保留报警时刻和轴号？", ["CNC-LT-500-FAULTS"]),
        ("磨床冷却液异常后，哪些情况不允许带电处理？", ["CNC-GR-630-FAULTS"]),
        ("设备高危报警的停机隔离和普通复位有什么区别？", ["ROB-IR-10-FAULTS", "CNC-MT-850-FAULTS"]),
    ],
    "EQ_MAINTENANCE_RECORD": [
        ("CNC-MT-850 保养完成后，班组应该记录哪些结果？", ["CNC-MT-850-MAINTENANCE"]),
        ("CNC-MT-1270 交接班时如何记录待跟踪的液压问题？", ["CNC-MT-1270-MAINTENANCE"]),
        ("ROB-IR-10 点检异常为什么要同步给质量和设备人员？", ["ROB-IR-10-MAINTENANCE"]),
        ("ROB-IR-20 维护后怎样记录示教点复核结果？", ["ROB-IR-20-MAINTENANCE"]),
        ("ROB-WL-12 试焊通过后，保养记录里应包含哪些内容？", ["ROB-WL-12-MAINTENANCE"]),
        ("CONV-VF-200 交接班要怎样记录累计运行小时和异常？", ["CONV-VF-200-MAINTENANCE"]),
        ("CONV-VF-300 传感器清洁后需要留下什么验证证据？", ["CONV-VF-300-MAINTENANCE"]),
        ("CONV-AS-600 分拣线保养后的质量闭环为什么不能省略？", ["CONV-AS-600-MAINTENANCE"]),
        ("CNC-LT-500 的保养记录如何避免只写“已完成”？", ["CNC-LT-500-MAINTENANCE"]),
        ("设备点检发现尺寸风险时，工艺批次信息为什么要纳入记录？", ["CNC-GR-630-MAINTENANCE"]),
    ],
}

MCP_CASES = {
    "EQ_DEVICE_MODEL_QUERY": [
        "查询 CNC-MT-1060 的参数、适用产线和组织范围", "查一下 CNC-GR-630 属于什么类别和车间",
        "装配车间 TEAM-A 有哪些工业机器人型号？", "MACHINING 车间有哪些变频输送线？",
        "ROB-IR-20 的关键参数与关联故障码是什么？", "查 CONV-AS-600 的分拣能力和推荐备件",
        "TEAM-M2 使用的数控机床型号有哪些？", "ROB-SC-04 适用什么装配线？",
        "查 CONV-VF-450 的载荷参数和车间归属", "CNC-MT-850 的推荐备件有哪些？",
    ],
    "EQ_DEVICE_STATUS_QUERY": [
        "CNC-01 现在是否正常运行，累计运行多少小时？", "CNC-02 当前状态和告警是什么？",
        "CNC-03 是生产还是保养状态？", "ROB-01 的实时运行状态请查询",
        "ROB-02 有什么报警，属于哪个班组？", "ROB-03 当前是否可以继续焊接？",
        "CONV-01 的运行小时和告警信息是什么？", "CONV-02 为什么在降速运行？",
        "CONV-03 属于哪个车间班组，状态如何？", "查 CNC-04 的实时状态和更新时间",
    ],
    "EQ_FAULT_CODE_QUERY": [
        "查询 CNC-101 的危险等级、原因和立即处置", "CNC-203 是什么故障，CNC-MT-1060 可以怎么排查？",
        "CNC-305 液压压力不足时为什么要停机？", "CNC-407 的处理步骤是什么？",
        "ROB-201 伺服过载会有哪些风险？", "ROB-302 碰撞检测触发后可以复位吗？",
        "ROB-404 焊接电流不稳的可能原因有哪些？", "ROB-503 真空不足时如何避免工件坠落？",
        "CONV-101 变频器过流的立即处置是什么？", "CONV-305 滚筒速度偏差应该检查什么？",
    ],
    "EQ_WORK_ORDER_QUERY": [
        "WO-20260719-001 现在谁在处理，预计何时完成？", "查询 WO-20260719-002 的状态和维修摘要",
        "WO-20260718-006 是什么类型的保养工单？", "WO-20260718-009 关联哪台输送线？",
        "WO-20260717-011 是否已经完成，验证结果是什么？", "CNC-02 的工单预计什么时候结束？请查 WO-20260719-001",
        "ROB-02 缺什么备件导致工单待处理？查询 WO-20260719-002", "TEAM-M2 当前这张保养工单是什么状态？WO-20260718-006",
        "装配一班 CONV-02 的工单处理人是谁？WO-20260718-009", "查询已完成的 ROB-WL-12 工单 WO-20260717-011",
    ],
    "EQ_SPARE_PART_QUERY": [
        "SP-CNC-SPINDLE-01 还有多少库存，放在哪？", "查询 SP-CNC-SERVO-01 的适配型号和替代件",
        "SP-CNC-HYD-01 在机加工车间还有库存吗？", "SP-ROB-REDUCER-02 的库位和兼容机器人是什么？",
        "SP-ROB-CABLE-02 是否适配 ROB-WL-12？", "SP-CONV-VFD-02 在装配一班可用库存是多少？",
        "SP-CONV-SENSOR-01 可以替代成什么型号？", "SP-CNC-SPINDLE-01 是否能给 CNC-MT-1060 使用？",
        "查 SP-ROB-REDUCER-02 的实时库存和组织归属", "SP-CONV-VFD-02 的替代备件是什么？",
    ],
    "EQ_MAINTENANCE_PLAN_QUERY": [
        "CNC-MT-850 的保养周期和下次保养时间是什么？", "查 CNC-MT-1060 的保养清单",
        "CNC-03 下次保养是什么时候？", "ROB-01 的保养周期和点检项有哪些？",
        "ROB-02 需要什么时候做下一次保养？", "ROB-WL-12 的保养需要检查哪些焊接部件？",
        "CONV-VF-200 的下次保养时间是什么？", "CONV-02 的点检清单和计划时间请查一下",
        "CONV-AS-600 的保养周期和下次时间？", "CNC-MT-1270 的液压保养计划是什么？",
    ],
}

SYSTEM_CASES = {
    "EQ_GREETING": ["你好", "设备运维助手在吗？", "早上好", "你能帮我做什么？", "我第一次使用，介绍一下能力", "谢谢你的说明", "晚上好", "我想了解设备支持范围", "收到，明白了", "请简单打个招呼"],
    "EQ_OUT_OF_SCOPE": ["帮我预测明天股票走势", "写一篇科幻小说", "北京天气怎么样", "帮我点一份午餐", "解释量子纠缠", "写一个 Java 排序算法", "推荐一部电影", "如何做减脂餐", "给我规划去上海旅游", "翻译一篇英文合同"],
}

EXPECTED_TOOLS = {
    "EQ_DEVICE_MODEL_QUERY": "device_model_query",
    "EQ_DEVICE_STATUS_QUERY": "device_status_query",
    "EQ_FAULT_CODE_QUERY": "fault_code_query",
    "EQ_WORK_ORDER_QUERY": "work_order_query",
    "EQ_SPARE_PART_QUERY": "spare_part_inventory_query",
    "EQ_MAINTENANCE_PLAN_QUERY": "maintenance_plan_query",
}


def row(query_id: str, intent: str, query: str, expected_docs: list[str] | None = None) -> dict:
    is_rag = intent in STATIC_CASES
    is_mcp = intent in EXPECTED_TOOLS
    return {
        "query_id": query_id,
        "query": query,
        "intent_l1": "EQUIPMENT_KB" if is_rag else "EQUIPMENT_MCP" if is_mcp else "EQUIPMENT_SYSTEM",
        "intent_l2": intent,
        "difficulty": ["easy", "medium", "hard"][(int(query_id[-3:]) - 1) % 3],
        "requires_rag": is_rag,
        "expected_answer_type": "knowledge_answer" if is_rag else "mcp_answer" if is_mcp else "no_rag",
        "expected_doc_ids": expected_docs or [],
        "expected_mcp_tool_id": EXPECTED_TOOLS.get(intent),
        "ground_truth": (
            "回答必须基于设备静态知识，涉及实时状态、工单、库存和下次保养时间时应明确转 MCP 查询。"
            if is_rag else f"必须路由到 MCP 工具 {EXPECTED_TOOLS[intent]}，不能用静态资料臆造实时结果。"
            if is_mcp else "应礼貌说明设备运维服务边界，不应触发知识库检索或 MCP 工具。"
        ),
        "eval_metrics": (
            ["intent_accuracy", "hit@5", "recall@5", "mrr@10", "kb_route_accuracy"]
            if is_rag else ["intent_accuracy", "mcp_route_accuracy", "mcp_invocation_rate"]
            if is_mcp else ["intent_accuracy", "no_retrieval_accuracy"]
        ),
    }


def main() -> None:
    rows: list[dict] = []
    sequence = 1
    for intent, cases in STATIC_CASES.items():
        for query, docs in cases:
            rows.append(row(f"EQ150-{sequence:03d}", intent, query, docs))
            sequence += 1
    for intent, cases in MCP_CASES.items():
        for query in cases:
            rows.append(row(f"EQ150-{sequence:03d}", intent, query))
            sequence += 1
    for intent, cases in SYSTEM_CASES.items():
        for query in cases:
            rows.append(row(f"EQ150-{sequence:03d}", intent, query))
            sequence += 1
    if len(rows) != 150:
        raise ValueError(f"expected 150 rows, got {len(rows)}")
    if len({item['query'] for item in rows}) != 150:
        raise ValueError("evaluation questions must be unique")
    DATASET_PATH.parent.mkdir(parents=True, exist_ok=True)
    DATASET_PATH.write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in rows), encoding="utf-8")
    print(f"generated {len(rows)} unique equipment evaluation questions: {DATASET_PATH}")


if __name__ == "__main__":
    main()
