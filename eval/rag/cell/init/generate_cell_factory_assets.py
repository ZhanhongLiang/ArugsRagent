from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KNOWLEDGE_ROOT = ROOT / "knowledge_base"

KB_SPECS = {
    "equipment_manual": {"name": "电芯设备手册与技术资料库", "collection_name": "kb-cell-equipment-manual", "embedding_model": "qwen-emb-8b"},
    "electrode_fault": {"name": "前段设备故障案例库", "collection_name": "kb-cell-electrode-fault", "embedding_model": "qwen-emb-8b"},
    "assembly_fault": {"name": "中段装配设备故障案例库", "collection_name": "kb-cell-assembly-fault", "embedding_model": "qwen-emb-8b"},
    "formation_fault": {"name": "后段注液化成故障案例库", "collection_name": "kb-cell-formation-fault", "embedding_model": "qwen-emb-8b"},
    "utility_fault": {"name": "公辅与环境设备故障库", "collection_name": "kb-cell-utility-fault", "embedding_model": "qwen-emb-8b"},
    "maintenance_standard": {"name": "点检保养与维修标准库", "collection_name": "kb-cell-maintenance-standard", "embedding_model": "qwen-emb-8b"},
    "process_standard": {"name": "工艺参数与作业标准库", "collection_name": "kb-cell-process-standard", "embedding_model": "qwen-emb-8b"},
    "quality_root_cause": {"name": "质量缺陷与根因知识库", "collection_name": "kb-cell-quality-root-cause", "embedding_model": "qwen-emb-8b"},
    "automation_software": {"name": "电气自动化与软件知识库", "collection_name": "kb-cell-automation-software", "embedding_model": "qwen-emb-8b"},
    "ehs_emergency": {"name": "安全、EHS 与应急知识库", "collection_name": "kb-cell-ehs-emergency", "embedding_model": "qwen-emb-8b"},
    "spare_tooling": {"name": "备件、易损件与工装治具库", "collection_name": "kb-cell-spare-tooling", "embedding_model": "qwen-emb-8b"},
}

WORKSHOPS = {
    "ELECTRODE": {"name": "电芯前段极片制造车间", "teams": {"ELECTRODE-MIX": "配料搅拌班", "ELECTRODE-COAT": "涂布烘干班", "ELECTRODE-CALENDER": "辊压班", "ELECTRODE-SLIT": "分切模切班"}},
    "ASSEMBLY": {"name": "电芯中段装配车间", "teams": {"ASSEMBLY-WIND": "卷绕班", "ASSEMBLY-STACK": "叠片班", "ASSEMBLY-WELD": "极耳焊接班", "ASSEMBLY-PACK": "入壳封装班"}},
    "FORMATION": {"name": "电芯后段注液化成车间", "teams": {"FORMATION-BAKE": "烘烤注液班", "FORMATION-FILL": "注液浸润班", "FORMATION-FORM": "化成 Degassing 班", "FORMATION-GRADE": "老化分容班"}},
    "QUALITY": {"name": "质量检测中心", "teams": {"QUALITY-IPQC": "过程质量班", "QUALITY-FQC": "终检可靠性班"}},
    "UTILITY": {"name": "公辅动力车间", "teams": {"UTILITY-DRYROOM": "干燥房真空班", "UTILITY-POWER": "气体冷却自动化班"}},
}

METADATA_FIELDS = [
    "factory_id", "workshop_id", "line_id", "process_stage", "process_code", "equipment_type", "equipment_model",
    "equipment_code", "subsystem", "doc_type", "fault_code", "alarm_code", "fault_family", "quality_defect",
    "cell_model", "cell_form", "polarity", "revision", "effective_status", "confidential_level", "supplier",
]


def intent(code: str, parent: str, name: str, kb_key: str, description: str, examples: list[str]) -> dict:
    return {"code": code, "name": name, "parent": parent, "kind": 0, "kb_key": kb_key, "description": description, "examples": examples, "top_k": 8}


def mcp(code: str, parent: str, name: str, tool_id: str, description: str, examples: list[str]) -> dict:
    return {"code": code, "name": name, "parent": parent, "kind": 2, "mcp_tool_id": tool_id, "description": description, "examples": examples, "top_k": 5}


def system(code: str, parent: str, name: str, description: str, examples: list[str]) -> dict:
    return {"code": code, "name": name, "parent": parent, "kind": 1, "description": description, "examples": examples, "top_k": 5}


def fault_intents(prefix: str, parent: str, kb_key: str, topics: list[tuple[str, str, str]]) -> list[dict]:
    result = []
    for suffix, name, keywords in topics:
        result.append(intent(
            f"CELL_FAULT_{prefix}_{suffix}", parent, name, kb_key,
            f"处理{keywords}相关的设备故障现象、原因排查、安全边界、修复和复验。",
            [f"{name}怎么排查？", f"{keywords}报警后先检查什么？"],
        ))
    return result


def intent_spec() -> dict:
    domains = {
        "CELL_FAULT_DIAGNOSIS": {"name": "设备故障诊断", "kind": 0, "description": "静态故障案例、诊断和处置知识"},
        "CELL_EQUIPMENT_OPERATION": {"name": "设备资料与操作", "kind": 0, "description": "设备手册、操作、参数与自动化资料"},
        "CELL_MAINTENANCE_REPAIR": {"name": "点检、保养与维修", "kind": 0, "description": "TPM、预防保养、维修与备件知识"},
        "CELL_PROCESS_QUALITY": {"name": "工艺与质量问题", "kind": 0, "description": "工艺标准、质量缺陷和根因追溯"},
        "CELL_EHS_EMERGENCY": {"name": "安全、EHS 与应急处置", "kind": 0, "description": "安全规程、危化品和应急响应"},
        "CELL_REALTIME_OPERATIONS": {"name": "实时生产与运维数据", "kind": 2, "description": "仅路由实时数据 MCP 工具"},
        "CELL_SYSTEM_INTERACTION": {"name": "系统交互与人工升级", "kind": 1, "description": "澄清、升级与会话兜底"},
    }
    categories = {
        "CELL_FAULT_ELECTRODE": {"parent": "CELL_FAULT_DIAGNOSIS", "name": "前段极片制造故障", "kind": 0},
        "CELL_FAULT_ASSEMBLY": {"parent": "CELL_FAULT_DIAGNOSIS", "name": "中段装配故障", "kind": 0},
        "CELL_FAULT_FORMATION": {"parent": "CELL_FAULT_DIAGNOSIS", "name": "后段注液化成故障", "kind": 0},
        "CELL_FAULT_UTILITY": {"parent": "CELL_FAULT_DIAGNOSIS", "name": "公辅、环境与自动化故障", "kind": 0},
        "CELL_OPERATION_MANUAL": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "设备手册、操作与报警", "kind": 0},
        "CELL_OPERATION_ELECTRODE": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "前段设备操作", "kind": 0},
        "CELL_OPERATION_ASSEMBLY": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "中段设备操作", "kind": 0},
        "CELL_OPERATION_FORMATION": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "后段与检测设备操作", "kind": 0},
        "CELL_OPERATION_UTILITY": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "公辅设备操作", "kind": 0},
        "CELL_OPERATION_AUTOMATION": {"parent": "CELL_EQUIPMENT_OPERATION", "name": "电气、自动化与软件", "kind": 0},
        "CELL_MAINTENANCE_STANDARD": {"parent": "CELL_MAINTENANCE_REPAIR", "name": "点检保养维修标准", "kind": 0},
        "CELL_MAINTENANCE_ELECTRODE": {"parent": "CELL_MAINTENANCE_REPAIR", "name": "前段设备保养", "kind": 0},
        "CELL_MAINTENANCE_ASSEMBLY": {"parent": "CELL_MAINTENANCE_REPAIR", "name": "中段设备保养", "kind": 0},
        "CELL_MAINTENANCE_FORMATION": {"parent": "CELL_MAINTENANCE_REPAIR", "name": "后段与公辅设备保养", "kind": 0},
        "CELL_MAINTENANCE_SPARE": {"parent": "CELL_MAINTENANCE_REPAIR", "name": "备件、易损件与工装", "kind": 0},
        "CELL_QUALITY_ELECTRODE": {"parent": "CELL_PROCESS_QUALITY", "name": "极片工艺与质量", "kind": 0},
        "CELL_QUALITY_ASSEMBLY": {"parent": "CELL_PROCESS_QUALITY", "name": "装配工艺与质量", "kind": 0},
        "CELL_QUALITY_FORMATION": {"parent": "CELL_PROCESS_QUALITY", "name": "注液化成与终检质量", "kind": 0},
        "CELL_QUALITY_TRACEABILITY": {"parent": "CELL_PROCESS_QUALITY", "name": "质量追溯与统计过程控制", "kind": 0},
        "CELL_PROCESS_GOVERNANCE": {"parent": "CELL_PROCESS_QUALITY", "name": "工艺变更与偏差管理", "kind": 0},
        "CELL_EHS_CHEMICAL": {"parent": "CELL_EHS_EMERGENCY", "name": "危化品与热失控", "kind": 0},
        "CELL_EHS_EQUIPMENT": {"parent": "CELL_EHS_EMERGENCY", "name": "设备安全与应急响应", "kind": 0},
        "CELL_EHS_SITE": {"parent": "CELL_EHS_EMERGENCY", "name": "现场作业与废弃物安全", "kind": 0},
        "CELL_MCP_DEVICE": {"parent": "CELL_REALTIME_OPERATIONS", "name": "设备、报警与工单实时查询", "kind": 2},
        "CELL_MCP_MATERIAL": {"parent": "CELL_REALTIME_OPERATIONS", "name": "备件与保养实时查询", "kind": 2},
        "CELL_SYSTEM_SUPPORT": {"parent": "CELL_SYSTEM_INTERACTION", "name": "澄清、升级与反馈", "kind": 1},
    }
    intents: list[dict] = []
    intents += fault_intents("ELECTRODE", "CELL_FAULT_ELECTRODE", "electrode_fault", [
        ("MIX_DISPERSION", "配料搅拌分散异常", "浆料分散、粘度、搅拌扭矩"),
        ("VACUUM_FILTER", "脱泡与过滤异常", "真空脱泡、过滤压差、气泡"),
        ("COATING_FEED", "涂布供料与涂布头异常", "供料压力、涂布头、条纹"),
        ("COATING_OVEN", "涂布烘箱温控异常", "烘箱温度、风量、干燥"),
        ("CALENDER", "辊压压力间隙温度异常", "辊压压力、间隙、温度"),
        ("SLITTING", "分切毛刺粉尘刀具异常", "分切毛刺、刀具、粉尘"),
        ("WEB_TENSION", "收放卷张力与纠偏异常", "张力、EPC、跑偏、收放卷"),
    ])
    intents += fault_intents("ASSEMBLY", "CELL_FAULT_ASSEMBLY", "assembly_fault", [
        ("WIND_TENSION", "卷绕张力与纠偏异常", "卷绕张力、极片跑偏、隔膜纠偏、EPC"),
        ("WIND_DIMENSION", "卷芯尺寸与变形异常", "卷芯直径、过松过紧、变形"),
        ("STACK_ALIGNMENT", "叠片对位与吸附异常", "叠片、对位、吸附、极片错位"),
        ("TAB_FORMING", "极耳成形与贴胶异常", "极耳成形、折弯、贴胶"),
        ("ULTRASONIC_WELD", "超声焊接异常", "虚焊、炸焊、焊接能量"),
        ("LASER_WELD", "激光焊接异常", "激光焊、焊穿、飞溅"),
        ("SEALING", "入壳与顶侧封异常", "入壳、顶封、侧封、封装"),
        ("SHORT_XRAY", "短路测试与 X-Ray 异常", "短路、Hi-pot、X-Ray"),
    ])
    intents += fault_intents("FORMATION", "CELL_FAULT_FORMATION", "formation_fault", [
        ("BAKE_VACUUM", "烘烤温度与真空异常", "真空烘烤、温度、残水"),
        ("FILL_QUANTITY", "注液量与称重异常", "注液量、称重、计量"),
        ("FILL_VACUUM", "注液真空与残气异常", "注液真空、残气、密封"),
        ("WETTING", "浸润时间异常", "浸润、静置、吸液"),
        ("FORMATION_CHANNEL", "化成通道与电源异常", "化成通道、电源、接触"),
        ("FORMATION_THERMAL", "化成温控与夹具异常", "化成温度、夹具、热管理"),
        ("DEGASSING", "Degassing 与二封异常", "排气、Degassing、二封"),
        ("AGING_GRADING", "老化分容与 OCV/IR 异常", "老化、分容、OCV、内阻、自放电"),
    ])
    intents += fault_intents("UTILITY", "CELL_FAULT_UTILITY", "utility_fault", [
        ("DRYROOM_DEW", "干燥房露点异常", "露点、除湿、干燥房"),
        ("VACUUM", "真空系统异常", "真空泵、管网、泄漏"),
        ("CDA_NITROGEN", "CDA 与氮气系统异常", "压缩空气、氮气、压力"),
        ("COOLING", "冷却水与冷水机异常", "冷却水、冷水机、流量"),
        ("EXHAUST_RECOVERY", "排风除尘与溶剂回收异常", "排风、除尘、溶剂回收"),
        ("PLC_IO", "PLC、I/O 与现场总线异常", "PLC、I/O、Profinet、EtherCAT"),
        ("MOTION_VISION", "伺服、机器人与视觉定位异常", "伺服、机器人、视觉定位"),
        ("MES_INTERFACE", "MES 通信与配方下发异常", "MES、接口、配方下发"),
    ])
    intents += [
        intent("CELL_OPERATION_START_STOP", "CELL_OPERATION_MANUAL", "开停机与联锁说明", "equipment_manual", "查询设备开停机、复位前置条件和安全联锁。", ["卷绕机怎么规范开机？", "报警复位前有哪些联锁条件？"]),
        intent("CELL_OPERATION_CHANGEOVER", "CELL_OPERATION_MANUAL", "换型换料与首件确认", "equipment_manual", "查询换型换料、首件确认和参数切换要求。", ["卷绕换型要确认哪些项目？", "注液换料怎么做？"]),
        intent("CELL_OPERATION_PARAMETER", "CELL_OPERATION_MANUAL", "参数设置与报警代码", "equipment_manual", "查询设备参数、报警代码和受控调整边界。", ["卷绕张力参数怎么查看？", "化成报警代码是什么意思？"]),
        intent("CELL_OPERATION_DRAWING", "CELL_OPERATION_MANUAL", "图纸、气液路与 I/O 查询", "equipment_manual", "查询机械、电气、气路、液路和 I/O 资料。", ["注液机真空管路图在哪？", "卷绕机 I/O 表怎么查？"]),
        intent("CELL_OPERATION_ELECTRODE_GUIDE", "CELL_OPERATION_ELECTRODE", "配料涂布辊压分切操作", "equipment_manual", "查询前段极片设备的规范操作、交接班与异常停机。", ["涂布机换卷怎么操作？", "辊压机停机后怎么复机？"]),
        intent("CELL_OPERATION_ASSEMBLY_GUIDE", "CELL_OPERATION_ASSEMBLY", "卷绕叠片焊接封装操作", "equipment_manual", "查询中段卷绕、叠片、焊接和封装设备的操作边界。", ["卷绕机穿带流程是什么？", "超声焊换治具怎么操作？"]),
        intent("CELL_OPERATION_FORMATION_GUIDE", "CELL_OPERATION_FORMATION", "烘烤注液化成分容操作", "equipment_manual", "查询后段烘烤、注液、化成、分容及检测设备操作。", ["化成柜上电前确认什么？", "OCV/IR 测试机开机流程？"]),
        intent("CELL_OPERATION_UTILITY_GUIDE", "CELL_OPERATION_UTILITY", "干燥房真空气体冷却操作", "equipment_manual", "查询干燥房、真空、气体和冷却系统的运行操作。", ["干燥房露点异常如何切换？", "冷水机启停注意什么？"]),
        intent("CELL_OPERATION_BACKUP", "CELL_OPERATION_AUTOMATION", "软件备份恢复与版本说明", "automation_software", "查询 PLC、HMI、视觉和机器人软件版本与备份恢复。", ["PLC 程序恢复前要注意什么？", "视觉配方如何备份？"]),
        intent("CELL_OPERATION_NETWORK", "CELL_OPERATION_AUTOMATION", "工业网络与 MES 接口", "automation_software", "查询工业网络、时间同步和 MES 接口排查方法。", ["设备为什么连不上 MES？", "现场总线掉站怎么排查？"]),
        intent("CELL_OPERATION_SERVO", "CELL_OPERATION_AUTOMATION", "伺服运动控制参数说明", "automation_software", "查询伺服、变频器和运动控制的受控参数。", ["卷绕伺服参数可以怎么调整？", "变频器参数如何备份？"]),
        intent("CELL_OPERATION_VISION", "CELL_OPERATION_AUTOMATION", "视觉配方与标定说明", "automation_software", "查询视觉定位、配方切换和标定方法。", ["视觉定位偏移如何标定？", "换型后视觉配方怎么确认？"]),
        intent("CELL_MAINTENANCE_DAILY", "CELL_MAINTENANCE_STANDARD", "日周月点检与清洁", "maintenance_standard", "查询日周月点检、清洁和记录要求。", ["卷绕机班前点检看什么？", "注液机周保养怎么做？"]),
        intent("CELL_MAINTENANCE_PREVENTIVE", "CELL_MAINTENANCE_STANDARD", "预防保养与校准", "maintenance_standard", "查询润滑、校准、预防性维护和验收要求。", ["辊压机精度如何校验？", "化成夹具多久校准？"]),
        intent("CELL_MAINTENANCE_REPAIR_ACCEPTANCE", "CELL_MAINTENANCE_STANDARD", "维修拆装与复验", "maintenance_standard", "查询检修拆装、LOTO、复机验证和维修验收。", ["更换真空泵后如何验收？", "维修后首件验证怎么做？"]),
        intent("CELL_MAINTENANCE_ELECTRODE_TPM", "CELL_MAINTENANCE_ELECTRODE", "前段涂布辊压分切 TPM", "maintenance_standard", "查询前段关键设备的 TPM、润滑、校准与易损件更换。", ["涂布头怎么保养？", "分切刀具寿命怎么管理？"]),
        intent("CELL_MAINTENANCE_ASSEMBLY_TPM", "CELL_MAINTENANCE_ASSEMBLY", "中段卷绕焊接封装 TPM", "maintenance_standard", "查询卷绕、焊接、封装设备的保养、精度和验收。", ["卷绕张力系统怎么保养？", "焊头需要怎样点检？"]),
        intent("CELL_MAINTENANCE_FORMATION_TPM", "CELL_MAINTENANCE_FORMATION", "后段注液化成分容 TPM", "maintenance_standard", "查询注液、化成、分容设备的预防维护和安全检修。", ["注液阀多久保养？", "化成夹具怎样校准？"]),
        intent("CELL_MAINTENANCE_UTILITY_TPM", "CELL_MAINTENANCE_FORMATION", "干燥房真空与冷却系统 TPM", "maintenance_standard", "查询干燥房、真空和冷却系统的维护标准。", ["真空泵保养周期？", "露点仪怎样校准？"]),
        intent("CELL_MAINTENANCE_CALIBRATION", "CELL_MAINTENANCE_STANDARD", "传感器与计量校准", "maintenance_standard", "查询张力、温度、压力、重量和电性能检测的校准要求。", ["张力传感器怎样校准？", "注液称重多久校验？"]),
        intent("CELL_SPARE_BOM", "CELL_MAINTENANCE_SPARE", "设备 BOM 与备件适配", "spare_tooling", "查询设备 BOM、备件规格和可替代关系。", ["卷绕机张力传感器备件型号？", "注液阀有哪些替代件？"]),
        intent("CELL_SPARE_TOOLING", "CELL_MAINTENANCE_SPARE", "易损件寿命与工装治具", "spare_tooling", "查询易损件寿命、更换周期和工装治具使用规范。", ["分切刀多久换？", "化成夹具怎么保养？"]),
        intent("CELL_SPARE_CONTROL", "CELL_MAINTENANCE_SPARE", "备件领用替代与受控变更", "spare_tooling", "查询备件领用、替代件评审和受控变更要求。", ["备件能直接用替代型号吗？", "关键备件怎么领用？"]),
        intent("CELL_TOOLING_ACCEPTANCE", "CELL_MAINTENANCE_SPARE", "工装治具验收与寿命管理", "spare_tooling", "查询工装治具验收、校准和报废标准。", ["新卷绕治具怎么验收？", "治具寿命到期如何处理？"]),
    ]
    intents += [
        intent("CELL_QUALITY_COATING", "CELL_QUALITY_ELECTRODE", "涂布厚度面密度与外观缺陷", "quality_root_cause", "分析面密度、厚度、条纹、露箔、颗粒和针孔。", ["涂布条纹通常和什么有关？", "面密度波动怎么追溯？"]),
        intent("CELL_QUALITY_ELECTRODE_DAMAGE", "CELL_QUALITY_ELECTRODE", "极片掉粉开裂褶皱与毛刺", "quality_root_cause", "分析极片掉粉、开裂、褶皱、毛刺和粉尘根因。", ["极片掉粉要查设备还是工艺？", "分切毛刺超标怎么查？"]),
        intent("CELL_PROCESS_ELECTRODE", "CELL_QUALITY_ELECTRODE", "前段工艺窗口与 SOP", "process_standard", "查询配料、涂布、辊压、分切的 SOP 与工艺窗口。", ["涂布换型工艺确认什么？", "辊压参数变更流程？"]),
        intent("CELL_QUALITY_ALIGNMENT", "CELL_QUALITY_ASSEMBLY", "极片错位与 Overhang 异常", "quality_root_cause", "分析极片错位、Overhang 和卷芯变形根因。", ["卷芯 Overhang 偏大怎么追溯？", "极片错位和张力有关吗？"]),
        intent("CELL_QUALITY_WELD_SEAL", "CELL_QUALITY_ASSEMBLY", "焊接与封装不良", "quality_root_cause", "分析虚焊、炸焊、焊穿、封装不良和内部短路风险。", ["极耳虚焊的根因有哪些？", "顶封不良怎么判断？"]),
        intent("CELL_PROCESS_ASSEMBLY", "CELL_QUALITY_ASSEMBLY", "中段工艺窗口与 SOP", "process_standard", "查询卷绕、叠片、焊接和封装的受控作业标准。", ["卷绕张力切换要走什么流程？", "激光焊参数能直接改吗？"]),
        intent("CELL_QUALITY_FILL", "CELL_QUALITY_FORMATION", "注液量浸润与气胀异常", "quality_root_cause", "分析注液不足过量、浸润不良和气胀。", ["注液不足会造成什么后果？", "气胀怎么判断根因？"]),
        intent("CELL_QUALITY_ELECTRICAL", "CELL_QUALITY_FORMATION", "容量内阻 OCV 与自放电异常", "quality_root_cause", "分析容量偏低、内阻偏高、OCV 和自放电异常。", ["内阻偏高如何向前段追溯？", "OCV 异常常见原因？"]),
        intent("CELL_QUALITY_FINAL", "CELL_QUALITY_FORMATION", "外观尺寸泄漏与绝缘不良", "quality_root_cause", "分析终检外观、尺寸、泄漏和绝缘问题。", ["软包电芯泄漏怎么处理？", "Hi-pot 不良怎么追溯？"]),
        intent("CELL_PROCESS_FORMATION", "CELL_QUALITY_FORMATION", "后段工艺窗口与放行", "process_standard", "查询注液、化成、老化、分容的工艺窗口和放行规则。", ["化成制度变更怎么审批？", "分容异常能否放行？"]),
        intent("CELL_QUALITY_BATCH_TRACE", "CELL_QUALITY_TRACEABILITY", "批次追溯与缺陷关联", "quality_root_cause", "查询缺陷、设备、工艺参数和批次之间的追溯关系。", ["容量异常怎么追溯到卷绕？", "这个批次受哪些报警影响？"]),
        intent("CELL_QUALITY_SPC", "CELL_QUALITY_TRACEABILITY", "SPC 异常与良率分析", "quality_root_cause", "分析 SPC 失控、良率波动和量测系统风险。", ["SPC 连续超限怎么处理？", "良率下降先看哪些数据？"]),
        intent("CELL_PROCESS_CHANGE", "CELL_PROCESS_GOVERNANCE", "工艺变更与版本生效", "process_standard", "查询工艺变更、版本生效、回退和首件确认要求。", ["卷绕参数变更怎么走流程？", "旧配方什么时候失效？"]),
        intent("CELL_PROCESS_DEVIATION", "CELL_PROCESS_GOVERNANCE", "工艺偏差与异常放行", "process_standard", "查询工艺偏差、临时放行和风险评审边界。", ["设备异常后能临时放行吗？", "偏差单要谁批准？"]),
    ]
    intents += [
        intent("CELL_EHS_ELECTROLYTE", "CELL_EHS_CHEMICAL", "电解液与溶剂泄漏处置", "ehs_emergency", "处理电解液、NMP 等泄漏、隔离、PPE 和升级。", ["电解液泄漏第一步做什么？", "NMP 泄漏如何处置？"]),
        intent("CELL_EHS_THERMAL", "CELL_EHS_CHEMICAL", "电芯发热冒烟鼓胀与热失控", "ehs_emergency", "处理异常电芯发热、冒烟、鼓胀和热失控风险。", ["化成柜电芯冒烟怎么办？", "鼓胀电芯如何隔离？"]),
        intent("CELL_EHS_HAZMAT", "CELL_EHS_CHEMICAL", "危化品 SDS 与废弃物处置", "ehs_emergency", "查询 SDS、危化品操作和废液废料处置。", ["电解液 SDS 在哪查？", "不良电芯怎么处置？"]),
        intent("CELL_EHS_LOTO", "CELL_EHS_EQUIPMENT", "高压 LOTO 与带能作业禁止", "ehs_emergency", "查询高压、真空、压力设备 LOTO 和禁令。", ["化成柜维修如何 LOTO？", "真空管路可以带压拆吗？"]),
        intent("CELL_EHS_DRYROOM", "CELL_EHS_EQUIPMENT", "干燥房、真空与压力安全", "ehs_emergency", "查询干燥房、真空和气路的安全要求。", ["干燥房进入有哪些要求？", "真空泵检修前要做什么？"]),
        intent("CELL_EHS_FIRE", "CELL_EHS_EQUIPMENT", "消防疏散与应急升级", "ehs_emergency", "查询消防、疏散和 EHS 应急升级流程。", ["化成区起火如何疏散？", "什么情况必须通知 EHS？"]),
        intent("CELL_EHS_PPE", "CELL_EHS_SITE", "PPE 与现场作业许可", "ehs_emergency", "查询 PPE、动火、受限空间和现场作业许可要求。", ["处理电解液要穿什么 PPE？", "维修需要办作业票吗？"]),
        intent("CELL_EHS_WASTE", "CELL_EHS_SITE", "废液废料与不良电芯处置", "ehs_emergency", "查询废液、废料和异常电芯隔离处置要求。", ["不良电芯怎么隔离？", "含电解液废料怎么处理？"]),
    ]
    intents += [
        mcp("CELL_MCP_DEVICE_MODEL", "CELL_MCP_DEVICE", "设备型号与适用范围查询", "device_model_query", "查询设备型号、参数和所属组织。", ["查询卷绕机型号", "装配车间有哪些设备？"]),
        mcp("CELL_MCP_DEVICE_STATUS", "CELL_MCP_DEVICE", "设备实时状态查询", "device_status_query", "查询设备当前状态和报警。", ["3号卷绕机现在什么状态？", "当前报警还在吗？"]),
        mcp("CELL_MCP_FAULT_CODE", "CELL_MCP_DEVICE", "实时故障代码查询", "fault_code_query", "查询故障代码的实时字典说明。", ["报警代码是什么含义？", "这个故障码危险吗？"]),
        mcp("CELL_MCP_WORK_ORDER", "CELL_MCP_DEVICE", "维修工单查询", "work_order_query", "查询工单状态、处理人和预计完成时间。", ["工单谁在处理？", "维修单到哪里了？"]),
        mcp("CELL_MCP_SPARE", "CELL_MCP_MATERIAL", "备件实时库存查询", "spare_part_inventory_query", "查询备件库存、库位和替代型号。", ["这个备件有库存吗？", "张力传感器在哪个库位？"]),
        mcp("CELL_MCP_MAINTENANCE", "CELL_MCP_MATERIAL", "保养计划查询", "maintenance_plan_query", "查询设备保养周期和下次保养。", ["卷绕机下次保养是什么时候？", "化成柜保养清单？"]),
    ]
    intents += [
        system("CELL_SYSTEM_GREETING", "CELL_SYSTEM_SUPPORT", "能力介绍", "说明系统能处理的电芯设备、质量、安全和实时查询范围。", ["你能做什么？", "能查哪些设备问题？"]),
        system("CELL_SYSTEM_CLARIFY", "CELL_SYSTEM_SUPPORT", "信息不足澄清", "当缺少设备、工序、报警或现象时引导补充。", ["设备有问题怎么办？", "帮我查一下异常。"]),
        system("CELL_SYSTEM_ENGINEER", "CELL_SYSTEM_SUPPORT", "转设备或工艺工程师", "把高风险或无法确认的问题升级给设备/工艺工程师。", ["转设备工程师", "需要工艺工程师确认。"]),
        system("CELL_SYSTEM_QUALITY", "CELL_SYSTEM_SUPPORT", "转质量工程师", "把质量判定和根因争议升级给质量工程师。", ["转质量工程师", "这个缺陷谁来判定？"]),
        system("CELL_SYSTEM_EHS", "CELL_SYSTEM_SUPPORT", "转 EHS 应急", "将泄漏、冒烟、热失控等高风险情况升级 EHS。", ["通知 EHS", "这里有电芯冒烟。"]),
        system("CELL_SYSTEM_FEEDBACK", "CELL_SYSTEM_SUPPORT", "答案反馈与纠错", "记录答案反馈、案例纠错和无答案转人工。", ["这个答案不对", "提交案例纠错。"]),
    ]
    return {"domains": domains, "categories": categories, "intents": intents}


DOCS = [
    ("equipment_manual", "ELECTRODE", "ELECTRODE-COAT", "前段设备操作与报警手册", "manual", "极片制造", "coating", "涂布机", "COATER-X1", "GZ01-E01-L02-COAT-01", "供料、涂布头、烘箱、张力与 EPC", "WORKSHOP"),
    ("equipment_manual", "ASSEMBLY", "ASSEMBLY-WIND", "卷绕机操作、参数与联锁手册", "manual", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "张力、EPC、收放卷、安全联锁", "TEAM"),
    ("equipment_manual", "FORMATION", "FORMATION-FILL", "注液机真空系统与操作手册", "manual", "后段注液化成", "filling", "注液机", "FILLER-F2", "GZ01-F01-L01-FILL-FILLER-01", "计量、真空、阀组、称重", "TEAM"),
    ("electrode_fault", "ELECTRODE", "ELECTRODE-COAT", "涂布条纹与供料压力波动故障案例", "case", "极片制造", "coating", "涂布机", "COATER-X1", "GZ01-E01-L02-COAT-01", "供料、涂布头、烘箱", "TEAM"),
    ("electrode_fault", "ELECTRODE", "ELECTRODE-SLIT", "分切毛刺粉尘与张力跑偏故障案例", "case", "极片制造", "slitting", "分切机", "SLITTER-S2", "GZ01-E01-L04-SLIT-SLITTER-01", "刀具、张力、纠偏、除尘", "TEAM"),
    ("assembly_fault", "ASSEMBLY", "ASSEMBLY-WIND", "卷绕极片跑偏与张力不稳故障案例", "case", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "张力、EPC、收放卷、隔膜", "TEAM"),
    ("assembly_fault", "ASSEMBLY", "ASSEMBLY-WELD", "极耳超声焊虚焊与炸焊故障案例", "case", "中段装配", "tab_welding", "超声焊机", "USW-U1", "GZ01-A01-L05-WELD-USW-01", "焊头、能量、压力、治具", "TEAM"),
    ("assembly_fault", "ASSEMBLY", "ASSEMBLY-PACK", "顶封侧封不良与短路测试异常案例", "case", "中段装配", "sealing", "封装机", "SEALER-P1", "GZ01-A01-L06-SEAL-SEALER-01", "封头、温控、压力、短路测试", "TEAM"),
    ("formation_fault", "FORMATION", "FORMATION-BAKE", "真空烘烤残水与温度异常故障案例", "case", "后段注液化成", "baking", "真空烘箱", "OVEN-B1", "GZ01-F01-L01-BAKE-OVEN-01", "真空、温度、残水", "TEAM"),
    ("formation_fault", "FORMATION", "FORMATION-FILL", "注液量不足与真空残气故障案例", "case", "后段注液化成", "filling", "注液机", "FILLER-F2", "GZ01-F01-L02-FILL-FILLER-01", "计量、真空、残气、称重", "TEAM"),
    ("formation_fault", "FORMATION", "FORMATION-FORM", "化成通道温控与 Degassing 异常案例", "case", "后段注液化成", "formation", "化成柜", "FORMATION-C8", "GZ01-F01-L03-FORM-CABINET-08", "通道、电源、温控、排气", "TEAM"),
    ("formation_fault", "FORMATION", "FORMATION-GRADE", "老化分容 OCV 内阻异常案例", "case", "后段注液化成", "grading", "分容柜", "GRADER-G6", "GZ01-F01-L04-GRADE-GRADER-06", "OCV、内阻、容量、自放电", "TEAM"),
    ("utility_fault", "UTILITY", "UTILITY-DRYROOM", "干燥房露点与真空系统异常案例", "case", "公辅动力", "dryroom", "除湿机", "DRYROOM-D2", "GZ01-U01-L01-DRY-DEHUMID-02", "露点、除湿、真空、泄漏", "TEAM"),
    ("utility_fault", "UTILITY", "UTILITY-POWER", "CDA 氮气冷却水与 PLC 通讯异常案例", "case", "公辅动力", "utility", "公辅系统", "UTILITY-U1", "GZ01-U01-L02-UTILITY-01", "CDA、氮气、冷却水、PLC、MES", "TEAM"),
    ("maintenance_standard", "ELECTRODE", "ELECTRODE-COAT", "前段设备点检保养与维修验收标准", "maintenance", "极片制造", "coating", "涂布机", "COATER-X1", "GZ01-E01-L02-COAT-01", "点检、润滑、校准、复验", "WORKSHOP"),
    ("maintenance_standard", "ASSEMBLY", "ASSEMBLY-WIND", "中段卷绕封装点检保养与维修验收标准", "maintenance", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "点检、张力、EPC、复验", "WORKSHOP"),
    ("maintenance_standard", "FORMATION", "FORMATION-FORM", "后段化成分容点检保养与 LOTO 标准", "maintenance", "后段注液化成", "formation", "化成柜", "FORMATION-C8", "GZ01-F01-L03-FORM-CABINET-08", "点检、LOTO、校准、复验", "WORKSHOP"),
    ("process_standard", "ELECTRODE", "ELECTRODE-COAT", "涂布辊压工艺窗口与换型 SOP", "sop", "极片制造", "coating", "涂布机", "COATER-X1", "GZ01-E01-L02-COAT-01", "工艺窗口、首件、配方", "TEAM"),
    ("process_standard", "ASSEMBLY", "ASSEMBLY-WIND", "卷绕张力与纠偏受控作业标准", "sop", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "张力、纠偏、换型、首件", "TEAM"),
    ("process_standard", "FORMATION", "FORMATION-FORM", "注液化成制度与放行标准", "sop", "后段注液化成", "formation", "化成柜", "FORMATION-C8", "GZ01-F01-L03-FORM-CABINET-08", "化成制度、温控、放行", "TEAM"),
    ("quality_root_cause", "QUALITY", "QUALITY-IPQC", "极片厚度面密度与掉粉根因矩阵", "quality", "质量检测", "electrode_quality", "检测设备", "GAUGE-Q1", "GZ01-Q01-L01-IPQC-GAUGE-01", "面密度、厚度、掉粉、毛刺", "TEAM"),
    ("quality_root_cause", "QUALITY", "QUALITY-IPQC", "卷芯错位 Overhang 与焊接缺陷根因矩阵", "quality", "质量检测", "assembly_quality", "X-Ray", "XRAY-Q2", "GZ01-Q01-L02-XRAY-01", "错位、Overhang、虚焊、炸焊", "TEAM"),
    ("quality_root_cause", "QUALITY", "QUALITY-FQC", "OCV 内阻容量泄漏与终检缺陷根因矩阵", "quality", "质量检测", "final_quality", "OCV/IR 测试机", "TESTER-Q3", "GZ01-Q01-L03-FQC-TESTER-01", "OCV、内阻、容量、泄漏、绝缘", "TEAM"),
    ("automation_software", "UTILITY", "UTILITY-POWER", "PLC I/O 工业网络与 MES 接口说明", "software", "公辅动力", "automation", "PLC", "PLC-S7", "GZ01-U01-L02-AUTO-PLC-01", "PLC、I/O、网络、MES", "TEAM"),
    ("automation_software", "ASSEMBLY", "ASSEMBLY-WIND", "卷绕伺服视觉与 HMI 备份恢复说明", "software", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "伺服、视觉、HMI、备份", "TEAM"),
    ("ehs_emergency", "UTILITY", "UTILITY-DRYROOM", "电解液溶剂泄漏与热失控应急处置", "ehs", "安全 EHS", "emergency", "通用", "EHS-GLOBAL", "GZ01-EHS-GLOBAL", "泄漏、热失控、PPE、隔离", "GLOBAL"),
    ("ehs_emergency", "UTILITY", "UTILITY-POWER", "高压 LOTO 真空压力与消防疏散规程", "ehs", "安全 EHS", "safety", "通用", "EHS-GLOBAL", "GZ01-EHS-GLOBAL", "LOTO、真空、压力、消防", "GLOBAL"),
    ("spare_tooling", "ASSEMBLY", "ASSEMBLY-WIND", "卷绕机张力系统备件与工装治具清单", "spare", "中段装配", "winding", "卷绕机", "WINDER-W3", "GZ01-A01-L03-WIND-WINDER-02", "张力传感器、EPC、治具", "TEAM"),
    ("spare_tooling", "FORMATION", "FORMATION-FILL", "注液阀真空密封件与工装治具清单", "spare", "后段注液化成", "filling", "注液机", "FILLER-F2", "GZ01-F01-L02-FILL-FILLER-01", "注液阀、密封件、真空、治具", "TEAM"),
    ("spare_tooling", "UTILITY", "UTILITY-DRYROOM", "干燥房真空系统易损件与备件清单", "spare", "公辅动力", "dryroom", "除湿机", "DRYROOM-D2", "GZ01-U01-L01-DRY-DEHUMID-02", "滤芯、真空泵、露点传感器", "TEAM"),
    ("equipment_manual", "QUALITY", "QUALITY-FQC", "OCV IR 泄漏与绝缘检测设备操作手册", "manual", "质量检测", "final_test", "检测设备", "TESTER-Q3", "GZ01-Q01-L03-FQC-TESTER-01", "OCV、内阻、泄漏、绝缘", "WORKSHOP"),
    ("maintenance_standard", "UTILITY", "UTILITY-DRYROOM", "公辅干燥房真空系统保养与应急维修标准", "maintenance", "公辅动力", "dryroom", "除湿机", "DRYROOM-D2", "GZ01-U01-L01-DRY-DEHUMID-02", "点检、滤芯、真空泵、露点", "WORKSHOP"),
    ("process_standard", "QUALITY", "QUALITY-FQC", "终检抽样判定与不良品隔离标准", "sop", "质量检测", "final_test", "检测设备", "TESTER-Q3", "GZ01-Q01-L03-FQC-TESTER-01", "抽样、判定、隔离、放行", "TEAM"),
]


def document_metadata(item: tuple) -> dict:
    _, workshop, team, _, doc_type, stage, process_code, equipment_type, model, equipment_code, subsystem, _ = item
    return {
        "factory_id": "GZ01", "workshop_id": workshop, "line_id": "L01", "process_stage": stage,
        "process_code": process_code, "equipment_type": equipment_type, "equipment_model": model,
        "equipment_code": equipment_code, "subsystem": subsystem, "doc_type": doc_type,
        "fault_code": "", "alarm_code": "", "fault_family": "mechanical,electrical,process",
        "quality_defect": "", "cell_model": "PHONE-4P45", "cell_form": "软包", "polarity": "公共",
        "revision": "V1.0", "effective_status": "有效", "confidential_level": "受控", "supplier": "示例供应商",
    }


def document_text(title: str, metadata: dict, subsystem: str) -> str:
    return f"""# {title}

## 元数据

```json
{json.dumps(metadata, ensure_ascii=False, indent=2)}
```

## 适用范围

适用于 `{metadata['factory_id']}` 工厂、`{metadata['workshop_id']}` 车间、`{metadata['process_code']}` 工序的 `{metadata['equipment_type']}`。设备对象为 `{metadata['equipment_code']}`，重点子系统：{subsystem}。

## 标准排查与处置

1. 先确认人员、设备、工艺和化学品安全边界；需要时停机、急停、隔离能源并执行 LOTO。
2. 记录报警代码、时间、产品型号、批次、当前参数和故障前发生的操作，不绕过安全联锁。
3. 按“供能/供料或真空 → 传感器与执行机构 → 机械与工装 → 受控工艺参数 → 软件通讯”的顺序排查。
4. 任何配方、化成制度、PLC 程序或受控参数变更必须走授权变更流程；禁止凭经验直接修改。
5. 修复后完成空载、首件/样件和质量确认；记录根因、备件、参数、停机时间及预防措施。

## 检索提示

可使用工序、设备型号、设备编号、子系统、报警码、故障族、产品型号和文档版本作为检索过滤条件。实时状态、报警、工单和库存必须经 MCP 查询，不能以本资料替代实时事实。
"""


def build_catalog() -> dict:
    catalog = {}
    for sequence, item in enumerate(DOCS, start=1):
        kb_key, workshop, team, title, _, _, _, _, _, _, subsystem, acl_scope = item
        code = f"CELL-{sequence:02d}"
        metadata = document_metadata(item)
        relative = f"knowledge_base/{kb_key}/{code}-{title}.md"
        path = ROOT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(document_text(title, metadata, subsystem), encoding="utf-8")
        scope = {"scope_type": acl_scope}
        if acl_scope != "GLOBAL":
            scope["workshop_code"] = workshop
        if acl_scope == "TEAM":
            scope["team_code"] = team
        catalog[code] = {"kb_key": kb_key, "rel_path": relative, "workshop_code": workshop, "team_code": team, "title": title, "metadata": metadata, "acl": scope}
    return catalog


def access_assignment_spec() -> dict:
    return {
        "principles": [
            "用户数据范围与知识资源 ACL 分离；前者说明用户可见组织，后者说明资源开放组织。",
            "文档 ACL 存在时覆盖知识库 ACL；工艺配方、软件备份、质量根因默认不全局开放。",
            "管理员具有现有 allAccess 管理权限；供应商仅分配被授权班组，禁止默认工作坊级权限。",
        ],
        "role_templates": {
            "operator": "TEAM：所属班组", "team_leader": "TEAM：所属班组", "equipment_technician": "WORKSHOP：所属车间",
            "process_engineer": "WORKSHOP：负责车间；产品配方文件仍由文档 ACL 约束", "quality_engineer": "WORKSHOP：QUALITY；按需要增加受审计的生产车间范围",
            "ehs_engineer": "WORKSHOP：全部五个车间；EHS GLOBAL 文档对已登录用户开放", "vendor_engineer": "TEAM：显式授权班组", "admin": "现有 admin allAccess",
        },
        "sample_assignments": {
            "cell_operator_wind": [{"scope_type": "TEAM", "workshop_code": "ASSEMBLY", "team_code": "ASSEMBLY-WIND"}],
            "cell_technician_assembly": [{"scope_type": "WORKSHOP", "workshop_code": "ASSEMBLY"}],
            "cell_quality_engineer": [{"scope_type": "WORKSHOP", "workshop_code": "QUALITY"}, {"scope_type": "WORKSHOP", "workshop_code": "FORMATION"}],
            "cell_ehs_engineer": [{"scope_type": "WORKSHOP", "workshop_code": code} for code in WORKSHOPS],
        },
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    catalog = build_catalog()
    (ROOT / "datasets").mkdir(parents=True, exist_ok=True)
    (ROOT / "state").mkdir(parents=True, exist_ok=True)
    (ROOT / "state" / ".gitkeep").touch()
    write_json(ROOT / "kb_specs.json", KB_SPECS)
    write_json(ROOT / "organization_spec.json", WORKSHOPS)
    write_json(ROOT / "access_assignment_spec.json", access_assignment_spec())
    write_json(ROOT / "intent_tree_spec.json", intent_spec())
    write_json(ROOT / "doc_catalog.json", catalog)
    print(f"generated cell factory assets: {len(KB_SPECS)} KBs, {len(WORKSHOPS)} workshops, {sum(len(value['teams']) for value in WORKSHOPS.values())} teams, {len(intent_spec()['intents'])} leaves, {len(catalog)} documents")


if __name__ == "__main__":
    main()
