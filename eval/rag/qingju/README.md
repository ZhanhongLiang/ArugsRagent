# 清居智能 RAG + MCP 评测资产

这是独立于茶叶场景的清居智能模拟商城评测工作区。静态资料走 RAG，订单、物流、库存、配件兼容与高德能力走 MCP；不要把模拟数据描述为真实商城生产数据。

## 资产规模

- 商品 SKU 文档：50 篇，编码与 `mcp-server` 中的模拟商城保持一致。
- 指南、手册、政策与 FAQ：16 篇。
- 知识库分布：{'product': 54, 'manual': 4, 'policy': 4, 'faq': 4}。
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
