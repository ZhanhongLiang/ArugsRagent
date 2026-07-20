from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "knowledge_base"
DATASET_ROOT = ROOT / "datasets"

KB_SPECS = {
    "models": {"name": "设备型号与参数库", "collection_name": "kb-equipment-models", "embedding_model": "qwen-emb-8b"},
    "faults": {"name": "设备故障诊断库", "collection_name": "kb-equipment-faults", "embedding_model": "qwen-emb-8b"},
    "maintenance": {"name": "设备点检保养库", "collection_name": "kb-equipment-maintenance", "embedding_model": "qwen-emb-8b"},
    "repair": {"name": "设备维修与备件库", "collection_name": "kb-equipment-repair", "embedding_model": "qwen-emb-8b"},
}

WORKSHOPS = {
    "MACHINING": {"name": "机加工车间", "teams": {"TEAM-M1": "机加一班", "TEAM-M2": "机加二班"}},
    "ASSEMBLY": {"name": "装配车间", "teams": {"TEAM-A": "装配一班", "TEAM-B": "装配二班"}},
}

MODELS = [
    ("CNC-MT-850", "立式数控加工中心 MT-850", "数控机床", "MACHINING", "TEAM-M1", "壳体精加工线", "行程 800x500x500 mm，主轴 12000 rpm，定位精度 ±0.008 mm", "CNC-101", "SP-CNC-SPINDLE-01"),
    ("CNC-MT-1060", "立式数控加工中心 MT-1060", "数控机床", "MACHINING", "TEAM-M1", "阀体与支架加工线", "行程 1000x600x600 mm，主轴 10000 rpm，刀库 24 把", "CNC-203", "SP-CNC-SERVO-01"),
    ("CNC-MT-1270", "重载数控加工中心 MT-1270", "数控机床", "MACHINING", "TEAM-M2", "大型底座加工线", "行程 1200x700x700 mm，主轴 8000 rpm，最大工件 1200 kg", "CNC-305", "SP-CNC-HYD-01"),
    ("CNC-LT-500", "数控车削中心 LT-500", "数控机床", "MACHINING", "TEAM-M2", "轴类车削线", "最大回转直径 500 mm，主轴 4500 rpm，12 工位刀塔", "CNC-203", "SP-CNC-CHUCK-01"),
    ("CNC-GR-630", "数控外圆磨床 GR-630", "数控机床", "MACHINING", "TEAM-M1", "精密磨削线", "最大磨削直径 320 mm，砂轮线速度 45 m/s，圆度 0.003 mm", "CNC-407", "SP-CNC-WHEEL-01"),
    ("ROB-IR-10", "六轴工业机器人 IR-10", "工业机器人", "ASSEMBLY", "TEAM-A", "小型部件上下料线", "额定负载 10 kg，臂展 1420 mm，重复定位 ±0.03 mm", "ROB-201", "SP-ROB-REDUCER-01"),
    ("ROB-IR-20", "六轴工业机器人 IR-20", "工业机器人", "ASSEMBLY", "TEAM-A", "焊接与搬运线", "额定负载 20 kg，臂展 1710 mm，防护等级 IP67", "ROB-302", "SP-ROB-REDUCER-02"),
    ("ROB-WL-12", "弧焊工业机器人 WL-12", "工业机器人", "ASSEMBLY", "TEAM-B", "结构件焊接线", "额定负载 12 kg，焊接电源 500 A，臂展 1468 mm", "ROB-404", "SP-ROB-CABLE-02"),
    ("ROB-PAL-25", "码垛工业机器人 PAL-25", "工业机器人", "ASSEMBLY", "TEAM-B", "成品码垛线", "额定负载 25 kg，节拍 10 次/分钟，最大垛高 1800 mm", "ROB-503", "SP-ROB-GRIPPER-01"),
    ("ROB-SC-04", "SCARA 装配机器人 SC-04", "工业机器人", "ASSEMBLY", "TEAM-A", "精密装配线", "额定负载 4 kg，水平臂长 600 mm，重复定位 ±0.02 mm", "ROB-302", "SP-ROB-BELT-01"),
    ("CONV-VF-200", "变频皮带输送线 VF-200", "变频输送线", "MACHINING", "TEAM-M1", "机加工物流输送线", "线体宽度 200 mm，速度 3-18 m/min，变频器 1.5 kW", "CONV-101", "SP-CONV-BELT-01"),
    ("CONV-VF-300", "变频皮带输送线 VF-300", "变频输送线", "ASSEMBLY", "TEAM-A", "装配物流输送线", "线体宽度 300 mm，速度 3-20 m/min，变频器 2.2 kW", "CONV-204", "SP-CONV-VFD-02"),
    ("CONV-VF-450", "变频滚筒输送线 VF-450", "变频输送线", "MACHINING", "TEAM-M2", "重载周转线", "线体宽度 450 mm，额定载荷 80 kg/m，变频器 3.0 kW", "CONV-305", "SP-CONV-ROLLER-01"),
    ("CONV-AS-600", "自动分拣输送线 AS-600", "变频输送线", "ASSEMBLY", "TEAM-B", "成品分拣线", "线体宽度 600 mm，分拣能力 1800 件/小时，光电检测 6 点", "CONV-204", "SP-CONV-SENSOR-01"),
    ("CONV-AS-800", "自动装箱输送线 AS-800", "变频输送线", "ASSEMBLY", "TEAM-B", "包装装箱线", "线体宽度 800 mm，额定载荷 120 kg/m，变频器 5.5 kW", "CONV-305", "SP-CONV-ROLLER-02"),
]


def document_text(code: str, name: str, category: str, workshop: str, team: str, line: str, specs: str,
                  fault: str, part: str, document_type: str) -> str:
    base = f"""# {name} {document_type}

## 适用范围

本文档适用于设备型号 `{code}`，设备类别为{category}，部署在{WORKSHOPS[workshop]['name']}{WORKSHOPS[workshop]['teams'][team]}，服务于{line}。文档中的型号、点检项、故障码和备件编码仅适用于本型号及明确列出的兼容设备，不应替代实时状态、工单或库存系统的查询结果。

## 设备基线

`{code}` 的关键技术参数为：{specs}。投入生产前，班组应确认设备铭牌型号、设备实例编号、工艺程序版本、夹具或末端执行器状态，并确认防护装置、急停回路和现场照明可用。操作人员不能因为设备可启动就绕过首件确认和安全检查；任何参数变更均需记录在班组交接记录中。

"""
    if document_type == "型号参数与工艺边界":
        detail = f"""## 参数与产线匹配

该型号用于{line}。选型或排产时需要同时核对负载、节拍、工件尺寸、工艺精度以及与上游下游设备的接口。对于超出额定能力的工件、夹具或节拍要求，应先由工艺和设备工程师评审，不允许用“短时运行”替代能力验证。设备型号、静态参数和适用产线可以从知识库检索；当前运行状态、运行时长与报警必须通过 `device_status_query` 查询。

## 运行限制

开机后先执行低速空载确认，再进入自动节拍。若发现异常振动、异响、温升、定位偏差或安全门联锁异常，应停止自动循环并保留报警信息。严禁在保护装置失效、润滑不足或工件夹紧状态不明时继续生产。交接班时应记录累计运行小时、当班异常、已采取动作与待跟踪事项。
"""
    elif document_type == "故障诊断与安全处置":
        detail = f"""## 典型故障码

本型号重点关注故障码 `{fault}`。收到报警时先确认危险等级、设备是否处于运动状态及人员是否位于危险区域。高危险等级报警应按急停和隔离流程处置；中低危险等级也必须记录报警时刻、轴号或站位、工件状态和复位结果。不能仅根据文字描述远程判断“可以继续运行”。

## 分级排查流程

第一步：停止自动运行，保护人员和工件安全。第二步：核对 HMI 报警全文、设备实例编号和当前工艺段。第三步：检查可见的线缆、润滑、冷却、传感器或机械卡滞迹象。第四步：由授权维修人员进行复位、试运行和结果记录。故障码的原因与处置属于静态知识；当前告警和运行状态需要调用 MCP 实时查询。

## 禁止事项

禁止短接安全回路、强制清除高危报警、带电拆卸功率部件或让未经授权人员进入机器人防护区。若故障重复出现，应创建或关联维修工单，并检查是否需要备件更换。
"""
    elif document_type == "点检 SOP 与保养计划":
        detail = f"""## 班前点检 SOP

班前按“环境、能源、安全、运动部件、工艺接口”五个层次执行点检：确认现场无障碍物和泄漏；确认电源、气源、液压或冷却条件正常；检查急停、防护门和光栅；观察导轨、减速机、皮带或滚筒等关键机构；最后执行低速空载动作。任一项不满足时不得进入自动生产。

## 周期保养

默认保养周期以累计运行小时为基准，实际下次保养时间应结合设备实例状态和工单计划确认。保养后必须记录实际完成项、发现问题、使用的润滑或替换件、试运行结果。计划周期和 SOP 可从知识库获取；某台设备的下次保养时间必须通过 `maintenance_plan_query` 查询。

## 质量闭环

点检异常应保留照片、报警代码、设备实例编号、工艺批次和交接班信息。对于影响尺寸、焊接质量或分拣准确性的异常，班组应同步通知质量与设备人员，不得只在口头交接中处理。
"""
    else:
        detail = f"""## 维修案例

某次该型号设备出现与 `{fault}` 相关的异常时，维修人员先完成停机隔离，再记录设备实例、累计运行时间、报警全文与现场现象。检查发现可维护部件存在磨损或污染后，按维修作业指导书完成清洁、紧固、校准或替换，并以低速、空载、单件和连续生产四个阶段验证恢复效果。案例说明的是处理方法，不代表所有异常都可直接复位。

## 备件与替代件

建议备件编码为 `{part}`。领用前需核对设备型号、适配关系、版本和库位；库存数量、库位和替代型号属于实时数据，应调用 `spare_part_inventory_query` 查询。缺件或需外购时，应在维修工单中记录风险和预计到货时间，不得使用未经工程确认的替代件。

## 工单闭环

工单应包含故障现象、原因判定、处置动作、备件消耗、处理人、验证结论和预计或实际完成时间。工单状态与处理人必须通过 `work_order_query` 查询，知识库中的案例不能作为实时工单信息。
"""
    appendix = f"""## 作业准备与过程控制

在{WORKSHOPS[workshop]['name']}{WORKSHOPS[workshop]['teams'][team]}执行涉及 `{code}` 的作业时，班组长应在开工前确认人员资质、设备点检状态、工艺文件版本、工装夹具或末端执行器状态，并确认{line}的上游来料与下游节拍条件。设备基线为“{specs}”，任何超出该基线的负载、节拍、工件尺寸、焊接电流、输送载荷或运行方式，都不能凭经验临时放行。现场发现异常时，操作员先执行停止自动循环、隔离危险区域、保留报警现场三个动作，再向班组长和设备维修人员报告。

## 维修协同与备件控制

`{code}` 的典型异常关联 `{fault}`，常用备件为 `{part}`。故障处理前应记录设备实例编号、发生时间、累计运行小时、当前工艺段、报警全文、可见现象以及是否影响产品质量。维修人员完成检查后，应区分清洁紧固、参数复核、机械调整和备件更换四类动作，并将实际动作写入工单。备件领用需核对适配型号、版本、批次和库位；若使用替代件，必须经过设备工程师确认。库存数量、具体库位、替代型号和工单处理人属于实时信息，不能由静态文档替代。

## 验证、交接与追溯

完成保养或维修后，按低速空载、单件验证、连续运行和质量复核的顺序确认恢复效果。对于{category}，连续运行阶段需要观察温升、振动、异响、定位、传感器信号或节拍稳定性；发现问题时立即回退到安全状态，不得以“偶尔正常”作为关闭工单的依据。交接班记录至少包含 `{code}` 的累计运行小时、异常编号、已完成动作、剩余风险、备件消耗、试运行结论和下一责任人。涉及尺寸、焊接质量、搬运安全或分拣准确率的异常，还应关联对应批次或质量记录。

## 数据权限与查询原则

本资料仅描述{WORKSHOPS[workshop]['name']}{WORKSHOPS[workshop]['teams'][team]}范围内 `{code}` 的静态知识。知识库检索应在车间和班组权限范围内执行，文档 ACL 优先于知识库 ACL。提问人未提供设备实例、型号、故障码或组织范围时，应先补充必要信息；不得将其他车间或班组的运行状态、工单、库存、保养时间当作当前设备事实。需要实时状态时调用设备状态工具，需要工单和库存时调用对应工具，需要下次保养时调用保养计划工具。
"""
    suffix = """## 检索回答边界

回答设备运维问题时，应优先说明适用型号、车间和班组范围。静态知识回答需要引用型号参数、SOP、故障处置或维修案例；涉及当前状态、工单、库存或下次保养时间时必须切换至对应 MCP 工具。信息不足时应追问设备实例编号、设备型号、故障码和组织范围，不能编造实时数据。
"""
    first_control = f"""## 岗位职责与异常升级

{WORKSHOPS[workshop]['name']}{WORKSHOPS[workshop]['teams'][team]}的操作人员负责按作业指导书执行设备启停、首件确认、班前点检和异常上报；班组长负责确认人员授权、生产切换条件、交接班记录和异常闭环状态；设备维修人员负责危险源隔离后的检修、参数复核、备件替换和试运行确认。对于 `{code}`，操作人员发现报警、异响、温升、跑偏、定位偏差、节拍波动或安全联锁异常时，不得自行扩大操作权限或跳过保护步骤。应先记录现场时间、设备实例编号、工位、当前任务、报警内容和已采取动作，并按“班组长、设备维修、工艺或质量”的路径升级。若异常影响人员安全、工件夹紧、焊接质量、搬运稳定性或产品可追溯性，必须立即停止自动运行并控制现场，直到授权人员明确恢复条件。

## 工艺变更与复产准入

生产切换、程序调整、夹具替换、末端工具更换、变频参数变化或传感器位置调整，都属于影响 `{code}` 稳定运行的变更。变更前需核对型号、适配范围、工艺版本和风险项；变更后应完成低速空载、单件确认、连续运行观察和质量复核。复产不能只依据报警消失，还应确认安全回路有效、关键参数回到受控范围、异常没有重复出现、交接人知晓剩余风险。对于跨班组或跨车间借用设备、工装或备件的情况，必须在组织范围和工单记录中明确责任归属，不能以口头授权替代系统记录。
"""
    second_control = f"""## 现场记录与审计证据

围绕 `{code}` 的每次异常、点检、保养和维修，应形成可追溯证据：包括设备实例、型号、车间、班组、操作人、维修人、发生时间、运行小时、故障码、工艺批次、处置步骤、备件消耗、试运行结果和质量结论。记录应足以让下一班组判断设备是否可继续生产，也足以让工程人员复盘重复故障。照片、报警截图和检测结果应与工单或班组记录关联保存。对于没有确认原因的异常，结论应写为“待验证”并明确下一步负责人和时间，不得以模糊的“已处理”关闭问题。

## 知识与实时数据的分界

本文档提供 `{code}` 的固定参数、作业规范、故障处置原则和维修案例，适合用于解释“应该怎样做”和“为什么这样做”。它不提供某台设备此刻是否运行、哪张工单处于处理状态、某备件当前剩余数量或具体下次保养时间。此类问题必须传入设备实例、工单号或备件编码，并通过相应 MCP 工具读取实时数据。回答中如同时涉及静态规则和实时事实，应先说明静态安全边界，再以实时查询结果作为当前决策依据，避免把历史案例误说成现场事实。
"""
    document = base + detail + appendix + suffix
    for control in (first_control, second_control):
        if len(document) >= 3200:
            break
        document += "\n" + control
    return document


def build_catalog() -> dict:
    catalog: dict[str, dict] = {}
    types = [
        ("models", "01_models", "型号参数与工艺边界"),
        ("faults", "02_faults", "故障诊断与安全处置"),
        ("maintenance", "03_maintenance", "点检 SOP 与保养计划"),
        ("repair", "04_repair", "维修案例与备件清单"),
    ]
    for code, name, category, workshop, team, line, specs, fault, part in MODELS:
        for kb_key, folder, document_type in types:
            doc_id = f"{code}-{kb_key.upper()}"
            path = KB_ROOT / folder / f"{doc_id}.md"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(document_text(code, name, category, workshop, team, line, specs, fault, part, document_type), encoding="utf-8")
            catalog[doc_id] = {
                "kb_key": kb_key,
                "rel_path": str(path.relative_to(ROOT)).replace("\\", "/"),
                "workshop_code": workshop,
                "team_code": team,
                "model": code,
                "document_type": document_type,
            }
    return catalog


def intent_spec() -> dict:
    return {
        "domains": {
            "EQUIPMENT_KB": "设备运维知识问答",
            "EQUIPMENT_MCP": "设备运维实时查询",
            "EQUIPMENT_SYSTEM": "设备运维会话兜底",
        },
        "categories": {
            "EQUIPMENT_MODEL_KB": ["EQUIPMENT_KB", "型号参数与产线"],
            "EQUIPMENT_FAULT_KB": ["EQUIPMENT_KB", "故障诊断与安全"],
            "EQUIPMENT_MAINTENANCE_KB": ["EQUIPMENT_KB", "点检保养与维修"],
            "EQUIPMENT_REALTIME": ["EQUIPMENT_MCP", "实时设备与工单数据"],
            "EQUIPMENT_CHAT": ["EQUIPMENT_SYSTEM", "问候与范围外问题"],
        },
        "intents": [
            {"code": "EQ_MODEL_SPEC", "name": "设备型号参数", "parent": "EQUIPMENT_MODEL_KB", "kind": 0, "kb_key": "models", "description": "查询设备型号、技术参数、适用产线和工艺边界。", "examples": ["CNC-MT-1060 的主轴和行程参数是什么？", "ROB-IR-20 适用于哪条产线？"]},
            {"code": "EQ_MODEL_COMPARE", "name": "设备型号对比", "parent": "EQUIPMENT_MODEL_KB", "kind": 0, "kb_key": "models", "description": "对比同类设备的额定能力、产线适配和使用边界。", "examples": ["CNC-MT-850 和 CNC-MT-1270 怎么选？", "ROB-IR-10 与 ROB-IR-20 的负载差异？"]},
            {"code": "EQ_FAULT_DIAGNOSIS", "name": "故障诊断与处置", "parent": "EQUIPMENT_FAULT_KB", "kind": 0, "kb_key": "faults", "description": "说明故障码含义、危险等级、可能原因和安全处置步骤。", "examples": ["CNC-203 是什么问题？", "机器人碰撞报警应该先做什么？"]},
            {"code": "EQ_INSPECTION_SOP", "name": "点检 SOP", "parent": "EQUIPMENT_MAINTENANCE_KB", "kind": 0, "kb_key": "maintenance", "description": "查询班前点检、保养步骤、安全检查和质量闭环要求。", "examples": ["变频输送线班前点检看哪些？", "机器人保养后如何验证？"]},
            {"code": "EQ_REPAIR_CASE", "name": "维修案例与备件规则", "parent": "EQUIPMENT_MAINTENANCE_KB", "kind": 0, "kb_key": "repair", "description": "查询维修案例、备件适配规则和工单闭环要求。", "examples": ["CNC-MT-1060 的伺服报警怎么维修？", "备件替代需要注意什么？"]},
            {"code": "EQ_OPERATION_SAFETY", "name": "设备操作安全", "parent": "EQUIPMENT_FAULT_KB", "kind": 0, "kb_key": "faults", "description": "查询停机隔离、危险区域控制和报警处置中的安全边界。", "examples": ["机器人碰撞报警后可以直接复位吗？", "输送线过流后谁可以试车？"]},
            {"code": "EQ_MAINTENANCE_RECORD", "name": "保养记录与交接", "parent": "EQUIPMENT_MAINTENANCE_KB", "kind": 0, "kb_key": "maintenance", "description": "查询保养记录、交接班信息和质量闭环要求。", "examples": ["保养后需要记录什么？", "交接班怎么记录设备异常？"]},
            {"code": "EQ_DEVICE_MODEL_QUERY", "name": "实时型号查询", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "device_model_query", "description": "按型号或类别查询设备参数、产线与组织归属。", "examples": ["查一下 CNC-MT-1060 的适用产线", "装配车间有哪些工业机器人？"]},
            {"code": "EQ_DEVICE_STATUS_QUERY", "name": "实时设备状态", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "device_status_query", "description": "查询设备运行状态、运行小时和当前报警。", "examples": ["CNC-02 现在什么状态？", "ROB-02 有什么告警？"]},
            {"code": "EQ_FAULT_CODE_QUERY", "name": "实时故障码查询", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "fault_code_query", "description": "查询故障码的原因、危险等级和立即处置。", "examples": ["查 CNC-203 的危险等级", "ROB-302 要怎么处置？"]},
            {"code": "EQ_WORK_ORDER_QUERY", "name": "维修工单查询", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "work_order_query", "description": "查询工单状态、处理人和预计完成时间。", "examples": ["WO-20260719-001 到哪里了？", "ROB-02 的工单谁在处理？"]},
            {"code": "EQ_SPARE_PART_QUERY", "name": "备件库存查询", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "spare_part_inventory_query", "description": "查询备件库存、库位和替代型号。", "examples": ["SP-CNC-SERVO-01 还有库存吗？", "SP-CONV-VFD-02 放在哪？"]},
            {"code": "EQ_MAINTENANCE_PLAN_QUERY", "name": "保养计划查询", "parent": "EQUIPMENT_REALTIME", "kind": 2, "mcp_tool_id": "maintenance_plan_query", "description": "查询保养周期、下次保养时间和点检清单。", "examples": ["ROB-02 下次保养是什么时候？", "CNC-MT-1270 的保养计划？"]},
            {"code": "EQ_GREETING", "name": "设备运维问候", "parent": "EQUIPMENT_CHAT", "kind": 1, "description": "普通问候和能力介绍，不触发检索或工具。", "examples": ["你好", "你能处理哪些设备问题？"]},
            {"code": "EQ_OUT_OF_SCOPE", "name": "设备运维范围外问题", "parent": "EQUIPMENT_CHAT", "kind": 1, "description": "与设备运维、故障、保养、工单或备件无关的问题。", "examples": ["帮我预测股票", "写一篇小说"]},
        ],
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    catalog = build_catalog()
    DATASET_ROOT.mkdir(parents=True, exist_ok=True)
    write_json(ROOT / "kb_specs.json", KB_SPECS)
    write_json(ROOT / "doc_catalog.json", catalog)
    write_json(ROOT / "intent_tree_spec.json", intent_spec())
    write_json(ROOT / "organization_spec.json", WORKSHOPS)
    print(f"generated equipment assets: {len(MODELS)} models, {len(catalog)} documents, 4 knowledge bases")


if __name__ == "__main__":
    main()
