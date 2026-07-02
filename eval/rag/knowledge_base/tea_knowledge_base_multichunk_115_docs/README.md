# 茗山茶事｜茶品类多 Chunk 测评知识库

这是按照 Ragent 文档型知识库结构构建的**茶品类模拟语料**。与上一版“每篇约 750–1292 字符、容易一篇一 chunk”的骨架语料不同，本版将主要业务文档扩展为可按 H2 语义边界拆分的长文档，适配以下分块配置：

```json
{
  "targetChars": 1400,
  "maxChars": 1800,
  "minChars": 600,
  "overlapChars": 0
}
```

## 目录与数量

```text
knowledge_base/
├── 01_product/  65 篇
│   ├── detail/  45 篇商品详情：2600–4200 字符，设计为 3 个语义 chunk
│   └── guide/   20 篇选购指南：3500–6000 字符，设计为 4 个语义 chunk
├── 02_manual/   25 篇
│   ├── app/       5 篇：3000–5000 字符，设计为 3 个语义 chunk
│   ├── network/   5 篇：3000–5000 字符，设计为 3 个语义 chunk
│   └── product/  15 篇：3000–5000 字符，设计为 3 个语义 chunk
├── 03_policy/   15 篇：2200–3500 字符，设计为 3 个语义 chunk
└── 04_faq/      10 篇
    ├── error_code/ 6 篇：900–1500 字符，设计为 1 个语义 chunk
    └── trouble/    4 篇：1800–2800 字符，设计为 2 个语义 chunk
```

## 使用说明

- 每篇正文使用稳定业务码，如 `PROD_TEA_001`、`GUIDE_TEA_010`、`POLICY_RETURN_001`，可以直接作为评估集 `expected_doc_ids`。
- 每篇文档顶部保留 YAML 元数据：`doc_id`、`kb`、`category`、`tags`、`chunk_design`。
- `document_catalog.csv` 可用于导入后检查业务码、文件路径、长度及预期语义 chunk 数。
- `chunk_audit.csv` 记录每个 H2 语义段的字符数。真实 Ragent 的最终 chunk 数仍由具体 Markdown splitter 实现决定；本包保证核心 H2 段均为可独立理解的 600+ 字符语义单元。
- 本包所有商品参数、政策、设备流程均为**模拟测试数据**，不适合直接用于真实销售、售后或健康宣称。

## 建议初始化顺序

1. 创建 product / manual / policy / faq 四个知识库。
2. 按 `01_` 至 `04_` 目录上传对应 Markdown。
3. 使用 `document_catalog.csv` 建立 `doc_id → ragent_doc_id` 映射。
4. 上传后等待异步分块完成，再检查每篇文档的 `chunkCount`。
5. 使用 `chunk_audit.csv` 抽查几篇文档，确认实际 chunk 数与设计预期接近。
