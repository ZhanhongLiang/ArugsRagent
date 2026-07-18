from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


METRIC_GUIDE = [
    ("run_success_rate", "运行成功率", "150 个请求中成功得到旁路评测结果的比例，用于发现接口异常、认证异常或依赖不可用。", "QJ150-001 至 QJ150-150"),
    ("intent_top1", "意图 Top-1 准确率", "最高分叶子意图是否等于标注意图，定位意图树边界、示例和问题重写是否需要调整。", "QJ150-001 至 QJ150-150"),
    ("doc_hit_at_5", "Doc Hit@5", "前 5 个召回文档中是否至少命中一个期望文档，衡量用户能否拿到关键业务证据。", "QJ150-001 至 QJ150-060（静态 RAG）"),
    ("doc_recall_at_5", "Doc Recall@5", "前 5 个文档覆盖了多少期望文档，适合检查比较题、政策题是否漏掉必要证据。", "QJ150-001 至 QJ150-060（静态 RAG）"),
    ("mrr_at_10", "MRR@10", "第一个期望文档的排名倒数，区分“召回到了但排得靠后”和“首位就命中”。", "QJ150-001 至 QJ150-060（静态 RAG）"),
    ("kb_route_accuracy", "知识库路由准确率", "静态知识题是否实际进入 KB 检索，衡量意图路由和检索通道是否被正确触发。", "QJ150-001 至 QJ150-060（静态 RAG）"),
    ("mcp_invocation_rate", "MCP 调用触发率", "需要实时数据的题目是否进入 MCP 分支，防止库存、订单和物流被静态文档回答。", "QJ150-061 至 QJ150-130（MCP）"),
    ("mcp_route_accuracy", "MCP 路由准确率", "MCP 题同时要求命中正确意图叶子且实际进入 MCP，检查工具节点与意图树绑定是否正确。", "QJ150-061 至 QJ150-130（MCP）"),
    ("no_retrieval_accuracy", "非检索收敛率", "问候和范围外问题没有误触发 KB 或 MCP，衡量过度检索和无关工具调用。", "QJ150-131 至 QJ150-150（系统兜底）"),
    ("eval_latency_ms_p95", "旁路检索延迟 P95", "95% 评测请求完成意图、改写、检索和 MCP 编排的耗时；它不是用户看到答案首字的 TTFT。", "QJ150-001 至 QJ150-150"),
]


def value(value) -> str:
    return "未测" if value is None else f"{value:.4f}"


def metric_samples(samples: list[dict], metric: str) -> tuple[list[dict], list[dict], list[dict]]:
    if metric == "run_success_rate":
        relevant = samples
        return relevant, [sample for sample in samples if sample["final_status"] == "success"], [sample for sample in samples if sample["final_status"] != "success"]
    if metric in {"eval_latency_ms_p95", "eval_latency_ms_mean", "eval_latency_ms_p50"}:
        relevant = [sample for sample in samples if sample.get("eval_latency_ms") is not None]
        return relevant, sorted(relevant, key=lambda sample: sample["eval_latency_ms"]), []
    sample_metric = {
        "doc_hit_at_5": "hit_at_5",
        "doc_recall_at_5": "recall_at_5",
        "mrr_at_10": "mrr_at_10",
    }.get(metric, metric)
    relevant = [sample for sample in samples if sample.get(sample_metric) is not None]
    return relevant, [sample for sample in relevant if sample[sample_metric] == 1.0], [sample for sample in relevant if sample[sample_metric] == 0.0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("scores")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    scores_path = Path(args.scores)
    scores = json.loads(scores_path.read_text(encoding="utf-8"))
    samples = scores["per_sample"]
    output = Path(args.out) if args.out else scores_path.with_name("指标分析.md")
    by_intent = defaultdict(list)
    for sample in samples:
        by_intent[sample["intent_l2"]].append(sample)

    lines = [
        "# 清居智能 150 题评测指标分析",
        "",
        "## 结论范围",
        "",
        f"- 本次实际执行 {scores['sample_count']} 条唯一问题，运行结果来自 `{scores['run']}`。",
        "- 题集分为静态 RAG 60 条、实时 MCP 70 条、问候与越界 20 条；每个叶子意图各 10 条。",
        "- 本报告中的检索、路由和旁路延迟均为真实接口结果；RAGAS 项目单列说明，未用规则或人工猜测伪造分数。",
        "",
        "## 总体指标",
        "",
        "| 指标 | 实测值 |",
        "| --- | --- |",
    ]
    for metric, label, _definition, _scope in METRIC_GUIDE:
        lines.append(f"| {label} | {value(scores['overall'].get(metric))} |")
    lines.append(f"| 旁路检索延迟均值（ms） | {value(scores['overall'].get('eval_latency_ms_mean'))} |")
    lines.append(f"| 旁路检索延迟 P50（ms） | {value(scores['overall'].get('eval_latency_ms_p50'))} |")

    lines += ["", "## 题集覆盖", "", "| 叶子意图 | 题号 | 数量 |", "| --- | --- | --- |"]
    for intent, items in sorted(by_intent.items()):
        ids = [item["query_id"] for item in items]
        lines.append(f"| {intent} | {ids[0]} 至 {ids[-1]} | {len(ids)} |")

    static_segment = scores["segments"]["static_rag"]
    mcp_segment = scores["segments"]["mcp"]
    system_segment = scores["segments"]["system"]
    mcp_failures = [sample["query_id"] for sample in samples if sample["expected_mcp"] and sample["mcp_route_accuracy"] == 0.0]
    if mcp_segment["mcp_invocation_rate"] == 0.0:
        mcp_diagnosis = "意图树可识别部分实时场景，但 Bootstrap 当前运行实例没有把工具执行器接入检索链路；优先检查 mcp-server 是否先启动、Bootstrap 是否在其后重启，以及启动日志中的工具自动发现记录。"
    else:
        mcp_diagnosis = f"MCP 工具已被当前 Bootstrap 实例发现并执行；剩余路由失败题为 {', '.join(mcp_failures) or '无'}，应针对其意图示例、问题表述和参数提取继续优化。"
    lines += [
        "",
        "## 本次实测诊断",
        "",
        f"- 静态 RAG：{static_segment['sample_count']} 条，意图 Top-1 为 {value(static_segment['intent_top1'])}，Hit@5 为 {value(static_segment['doc_hit_at_5'])}，Recall@5 为 {value(static_segment['doc_recall_at_5'])}，KB 路由准确率为 {value(static_segment['kb_route_accuracy'])}。优先复核 Hit@5=0 的对比、手册和政策题，而不是只调 Prompt。",
        f"- MCP：{mcp_segment['sample_count']} 条，意图 Top-1 为 {value(mcp_segment['intent_top1'])}，MCP 调用触发率为 {value(mcp_segment['mcp_invocation_rate'])}，路由准确率为 {value(mcp_segment['mcp_route_accuracy'])}。{mcp_diagnosis}",
        f"- 系统兜底：{system_segment['sample_count']} 条，意图 Top-1 为 {value(system_segment['intent_top1'])}，非检索收敛率为 {value(system_segment['no_retrieval_accuracy'])}。范围外题仍被检索时，应补充 QJ_OUT_OF_SCOPE 的示例和低置信度拒答边界。",
        f"- 性能：旁路检索 P95 为 {value(scores['overall'].get('eval_latency_ms_p95'))} ms，最慢题见下方延迟条目。它覆盖改写、意图、检索和 MCP 编排，不包含流式模型回答的 TTFT。",
    ]

    lines += ["", "## 指标解读与对应题目", ""]
    for metric, label, definition, scope in METRIC_GUIDE:
        relevant, passed, failures = metric_samples(samples, metric)
        if metric in {"eval_latency_ms_p95", "eval_latency_ms_mean", "eval_latency_ms_p50"}:
            examples = ", ".join(f"{item['query_id']}({item['eval_latency_ms']}ms)" for item in passed[:5]) or "无"
            issue_text = "最慢样本：" + ", ".join(f"{item['query_id']}({item['eval_latency_ms']}ms)" for item in passed[-10:])
        else:
            examples = ", ".join(item["query_id"] for item in passed[:5]) or "无"
            issue_text = ", ".join(item["query_id"] for item in failures[:12]) or "无"
        lines += [
            f"### {label}",
            "",
            f"作用：{definition}",
            "",
            f"对应题目：{scope}，共 {len(relevant)} 条。",
            "",
            f"本次实测：{value(scores['overall'].get(metric))}；通过示例：{examples}。",
            "",
            f"待优化样本：{issue_text}。完整逐题结果见同目录 score JSON。",
            "",
        ]

    lines += ["## RAGAS 五项指标", ""]
    ragas = scores["ragas"]
    lines += [
        "本次未输出 RAGAS 数值：当前 Python 环境未安装 `ragas`，且本次 Runner 调用的是只返回检索证据的 `/rag/eval`，不包含 `/rag/v3/chat` 的完整 SSE 答案。",
        "",
        "| 指标 | 作用 | 本题集中应覆盖的题目 | 本次状态 |",
        "| --- | --- | --- | --- |",
        "| faithfulness | 回答中的事实是否被检索上下文支撑，防止编造保修、库存和物流事实。 | QJ150-001 至 QJ150-060，重点政策与故障题 QJ150-051 至 QJ150-060 | 待 SSE + Judge |",
        "| answer_relevancy | 最终回答是否直接回应用户问题，发现跑题或改写偏移。 | QJ150-001 至 QJ150-060，重点选购与对比题 QJ150-001 至 QJ150-030 | 待 SSE + Judge |",
        "| answer_correctness | 最终回答是否与自然语言标准答案一致，是端到端报警信号。 | QJ150-001 至 QJ150-060 | 待补充逐题自然语言标准答案并执行 Judge |",
        "| context_precision | 返回的 chunk 是否相关且排在前面，发现噪声和重排问题。 | QJ150-001 至 QJ150-060，重点对比题 QJ150-021 至 QJ150-030 | 待 ragas Judge |",
        "| context_recall | 上下文是否覆盖标准答案需要的信息，发现知识缺失、分块和漏召。 | QJ150-001 至 QJ150-060，重点手册/政策/FAQ 题 QJ150-031 至 QJ150-060 | 待 ragas Judge |",
        "",
        "不能把本报告的 Doc Hit@5、Recall@5 或 MRR 当成 RAGAS。前者是业务文档 ID 的集合匹配，后者是 Judge 对回答和 chunk 语义的评分；两者要配合使用。",
        "",
        "## 优化优先级",
        "",
        "1. 先按上方“待优化样本”回看问题、预测意图、召回文档和 MCP 标记；同一叶子连续失败优先补意图示例或知识证据。",
        "2. Hit@5 或 Recall@5 低时，先检查期望文档是否已入库、分块是否包含完整条件、意图是否指向正确知识库，再调整重排和 TopK。",
        "3. MCP 触发率或路由准确率低时，检查实时意图节点的 `mcpToolId`、工具自动发现、参数描述与训练示例；不要尝试用静态商品文档兜底实时结果。",
        "4. P95 高时，用 Trace 拆分问题重写、意图、检索、MCP 和模型阶段。本次 `eval_latency_ms` 不含完整 SSE 首字时间，TTFT 必须由流式 Runner 单独记录。",
        "5. 接入 RAGAS 前，先为 60 条静态题补全逐题自然语言 `ground_truth`，再以同一批录制结果做多轮 Judge、人工复核低分政策题和 A/B Diff。",
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
