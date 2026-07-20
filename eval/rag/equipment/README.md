# 设备运维 RAG 与 MCP 资产

本目录只保存制造业设备运维场景的初始化资产：15 个设备型号、4 个知识库、两车间四班组 ACL、13 个意图叶子节点和 150 条评测题。静态型号、SOP、故障与维修案例走 RAG；实时状态、工单、库存和保养时间走设备 MCP。

初始化前先启动 `mcp-server`，再启动 `bootstrap`，使 Bootstrap 能通过 `tools/list` 发现六个设备工具。随后执行：

```powershell
$env:RAG_EVAL_ROOT = (Resolve-Path "eval/rag/equipment").Path
python eval/rag/equipment/init/generate_equipment_assets.py
python eval/rag/init/create_kbs.py
python eval/rag/init/upload_docs.py --dry-run
python eval/rag/init/upload_docs.py --sleep 1
python eval/rag/init/build_intent_tree.py
python eval/rag/equipment/init/configure_equipment_access.py
python eval/rag/equipment/init/verify_chunks.py
python eval/rag/equipment/init/generate_equipment_eval_set.py
```

若 `verify_chunks.py` 发现历史上传的文档仅生成一个切片，先重新生成资料，再只重传这些文档并重新写入 ACL：

```powershell
python eval/rag/equipment/init/generate_equipment_assets.py
python eval/rag/equipment/init/reupload_under_chunked_documents.py
python eval/rag/equipment/init/configure_equipment_access.py
python eval/rag/equipment/init/verify_chunks.py
```

在替换旧业务资产前执行备份和清理：

```powershell
python eval/rag/init/backup_and_reset_business_assets.py
python eval/rag/init/backup_and_reset_business_assets.py --apply
```

清理脚本仅通过现有 API 删除已备份的知识库、文档、资源 ACL 与意图树；不删除用户、组织表、通用代码或数据库表结构。备份落在 `backups/`，不纳入版本控制。
