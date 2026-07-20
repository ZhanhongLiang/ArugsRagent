# 设备运维 RAG / MCP 评测资产

当前业务场景为制造业设备运维：数控机床、工业机器人和变频输送线。静态型号资料、故障诊断、点检 SOP 与维修案例由 RAG 检索；设备状态、工单、库存和保养时间由 MCP 查询。

```text
eval/rag/equipment/
├── knowledge_base/       # 60 篇设备文档，4 个知识库，每篇 2 个切片
├── intent_tree_spec.json # 15 个叶子意图：7 个 RAG、6 个 MCP、2 个系统意图
├── organization_spec.json# 2 个车间、4 个班组
└── datasets/             # 150 条覆盖 RAG、MCP、系统兜底的评测题
```

初始化前先启动 `mcp-server`，再启动 `bootstrap`，让 Bootstrap 通过 MCP `tools/list` 注册六个设备工具。随后执行：

```powershell
$env:RAG_EVAL_ROOT = (Resolve-Path "eval/rag/equipment").Path
$env:RAGENT_BASE_URL = "http://localhost:9090/api/ragent"
$env:RAGENT_USERNAME = "admin"
$env:RAGENT_PASSWORD = "admin"

python eval/rag/equipment/init/generate_equipment_assets.py
python eval/rag/init/create_kbs.py
python eval/rag/init/upload_docs.py --sleep 1
python eval/rag/init/build_intent_tree.py
python eval/rag/equipment/init/configure_equipment_access.py
python eval/rag/equipment/init/verify_chunks.py
```

运行 150 条评测并计算设备场景指标：

```powershell
python eval/rag/run_eval.py --sleep 0.5
python eval/rag/equipment/score_equipment.py eval/rag/equipment/runs/<run>.jsonl
python eval/rag/report/markdown.py eval/rag/equipment/runs/<run>_equipment_scores.json --out-dir eval/rag/equipment/reports/<run>
```
