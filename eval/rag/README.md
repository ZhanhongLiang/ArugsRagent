# 茶品 RAG 评测初始化与执行流程

本目录对应《AI大模型Ragent项目》78-88 的评测体系，当前知识库内容为茶品业务场景。

本次生成策略：保留 `eval/rag/knowledge_base` 文档目录不动，只重新生成评测配置、文档索引、意图树配置、评估集和说明文档。

## 当前茶品知识库分布

```text
eval/rag/knowledge_base/
├── 01_product/  # 商品与选购资料，65 篇
├── 02_manual/   # APP、设备、茶具使用手册，25 篇
├── 03_policy/   # 售后、退换货、物流、发票会员政策，15 篇
└── 04_faq/      # 错误码与故障排查，10 篇
```

## 关键文件

```text
eval/rag/kb_specs.json              # 茶品 4 个 KB 配置
eval/rag/doc_catalog.json           # 115 篇文档业务码索引
eval/rag/intent_tree_spec.json      # 茶品意图树定义
eval/rag/tea_eval_doc_pools.json    # 每个意图对应的评测证据文档池
eval/rag/datasets/eval_set_v1.jsonl      # 主力评估集，20 条
eval/rag/datasets/eval_set_v1_all.jsonl  # 全量评估集，150 条
```

## 初始化顺序

```powershell
$env:RAGENT_BASE_URL="http://localhost:9090/api/ragent"
$env:RAGENT_USERNAME="admin"
$env:RAGENT_PASSWORD="admin"

python eval/rag/init/create_kbs.py
python eval/rag/init/upload_docs.py --dry-run
python eval/rag/init/upload_docs.py --sleep 1
python eval/rag/init/build_intent_tree.py
```

说明：

- `create_kbs.py`：创建 4 个茶品知识库，生成 `eval/rag/state/kb_ids.json`。
- `upload_docs.py --dry-run`：只检查本地 115 篇文档分布，不上传。
- `upload_docs.py --sleep 1`：上传 115 篇文档，并触发分块。
- `build_intent_tree.py`：创建 DOMAIN/CATEGORY/TOPIC 三层意图树，生成 `eval/rag/state/intent_ids.json`。

如果已经上传过旧文档，需要先重置后端知识库，避免旧 chunk 和旧向量残留：

```powershell
python eval/rag/init/reset_kbs.py --yes
```

然后再按初始化顺序重新执行。

## 评测顺序

主力 20 条：

```powershell
python eval/rag/run_eval.py --dataset eval/rag/datasets/eval_set_v1.jsonl
```

全量 150 条：

```powershell
python eval/rag/run_eval.py --dataset eval/rag/datasets/eval_set_v1_all.jsonl --sleep 0.5
```

计算自建指标：

```powershell
python eval/rag/metrics/score_builtin.py eval/rag/runs/<run>.jsonl
```

生成报告：

```powershell
python eval/rag/report/markdown.py eval/rag/runs/<run>_scores.json --out-dir eval/rag/reports/<run>
```