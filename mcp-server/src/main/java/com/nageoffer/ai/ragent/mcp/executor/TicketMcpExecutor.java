/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 客户技术支持工单的离线模拟 MCP 工具。
 *
 * <p>工具按当天固定随机种子生成最近 30 天的工单，使汇总、列表和统计在同一天内稳定可复现。</p>
 */
@Slf4j
@Component
public class TicketMcpExecutor {

    /** MCP 工具唯一标识。 */
    private static final String TOOL_ID = "ticket_query";

    /** 模拟数据使用的地区、产品、状态、优先级与问题分类枚举。 */
    private static final List<String> REGIONS = List.of("华东", "华南", "华北", "西南", "西北");
    private static final List<String> PRODUCTS = List.of("企业版", "专业版", "基础版");
    private static final String STATUS_PENDING = "待处理";
    private static final String STATUS_IN_PROGRESS = "处理中";
    private static final String STATUS_RESOLVED = "已解决";
    private static final String STATUS_CLOSED = "已关闭";
    private static final List<String> STATUSES = List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_RESOLVED, STATUS_CLOSED);
    private static final List<String> PRIORITIES = List.of("紧急", "高", "中", "低");
    private static final List<String> CATEGORIES = List.of("功能异常", "性能问题", "安装部署", "使用咨询", "数据问题", "权限问题");

    /** 按地区生成客户名称与工程师，保证工单的地域关系合理。 */
    private static final Map<String, List<String>> CUSTOMERS_BY_REGION = Map.of(
            "华东", List.of("腾讯科技", "阿里巴巴", "字节跳动", "网易公司"),
            "华南", List.of("美团点评", "京东集团", "小米科技", "格力电器"),
            "华北", List.of("百度在线", "华为技术", "中兴通讯", "用友网络"),
            "西南", List.of("科大讯飞", "金蝶软件", "三一重工", "中联重科"),
            "西北", List.of("浪潮集团", "东软集团", "美的集团", "海尔智家")
    );

    private static final Map<String, List<String>> ENGINEERS_BY_REGION = Map.of(
            "华东", List.of("工程师A1", "工程师A2"),
            "华南", List.of("工程师B1", "工程师B2"),
            "华北", List.of("工程师C1", "工程师C2"),
            "西南", List.of("工程师D1", "工程师D2"),
            "西北", List.of("工程师E1", "工程师E2")
    );

    /** 用于生成工单标题的典型技术支持问题模板。 */
    private static final List<String> ISSUE_TEMPLATES = List.of(
            "系统登录后页面白屏无法操作",
            "报表导出功能超时失败",
            "用户权限配置不生效",
            "数据同步延迟超过预期",
            "批量导入数据格式校验异常",
            "API接口调用返回500错误",
            "定时任务未按计划执行",
            "搜索功能结果不准确",
            "通知消息无法正常推送",
            "文件上传大小限制配置无效",
            "仪表盘数据展示不一致",
            "多租户数据隔离存在问题",
            "审批流程节点卡住无法流转",
            "移动端页面适配显示异常",
            "数据备份任务执行失败"
    );

    /** 当日模拟数据缓存及其日期键，避免每次 tools/call 都重新生成并改变统计结果。 */
    private List<TicketRecord> cachedData;
    private String cacheKey;

    /** 注册工单查询工具和同步处理器。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification ticketToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    /** 构建可由 LLM 参数提取器理解的工单筛选 Schema。 */
    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("region", Map.of(
                "type", "string",
                "description", "地区筛选：华东、华南、华北、西南、西北，不填则查询全国",
                "enum", List.of("华东", "华南", "华北", "西南", "西北")
        ));

        properties.put("status", Map.of(
                "type", "string",
                "description", "工单状态筛选：待处理、处理中、已解决、已关闭，不填则查询全部状态",
                "enum", STATUSES
        ));

        properties.put("priority", Map.of(
                "type", "string",
                "description", "优先级筛选：紧急、高、中、低，不填则查询全部优先级",
                "enum", List.of("紧急", "高", "中", "低")
        ));

        properties.put("product", Map.of(
                "type", "string",
                "description", "产品筛选：企业版、专业版、基础版，不填则查询全部产品",
                "enum", List.of("企业版", "专业版", "基础版")
        ));

        properties.put("customerName", Map.of(
                "type", "string",
                "description", "客户名称关键字，支持模糊匹配"
        ));

        properties.put("queryType", Map.of(
                "type", "string",
                "description", "查询类型：summary(汇总概览)、list(工单列表)、stats(统计分析)",
                "enum", List.of("summary", "list", "stats"),
                "default", "summary"
        ));

        properties.put("limit", Map.of(
                "type", "integer",
                "description", "返回记录数限制，默认10",
                "default", 10
        ));

        // 所有筛选条件都可选；未提供时表示全量查询。
        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询客户技术支持工单数据，支持按地区、状态、优先级、产品、客户等维度筛选，支持汇总概览、工单列表、统计分析等多种查询")
                .inputSchema(inputSchema)
                .build();
    }

    /** 解析工具参数，筛选缓存数据，并按 summary/list/stats 输出对应文本。 */
    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            // 兼容 MCP 请求没有 arguments 的情况。
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String region = stringArg(args, "region");
            String status = stringArg(args, "status");
            String priority = stringArg(args, "priority");
            String product = stringArg(args, "product");
            String customerName = stringArg(args, "customerName");
            String queryType = stringArg(args, "queryType");
            Integer limit = intArg(args, "limit");

            // 使用 Schema 默认值，并避免非正数 limit 造成空结果。
            if (queryType == null || queryType.isBlank()) queryType = "summary";
            if (limit == null || limit <= 0) limit = 10;

            // 先取得稳定的当日数据，再应用所有可选筛选条件。
            List<TicketRecord> allData = getOrGenerateData();
            List<TicketRecord> filtered = filterData(allData, region, status, priority, product, customerName);

            // 根据查询类型选择不同粒度的结果视图。
            String result = switch (queryType) {
                case "list" -> buildListResult(filtered, limit);
                case "stats" -> buildStatsResult(filtered);
                default -> buildSummaryResult(filtered, region, status, priority, product);
            };

            log.info("MCP 工具调用完成, toolId={}, queryType={}, region={}, status={}, elapsed={}ms",
                    TOOL_ID, queryType, region, status, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    /** 构建总量、状态分布、解决率和高优先级风险的概览。 */
    private String buildSummaryResult(List<TicketRecord> data, String region, String status,
                                      String priority, String product) {
        int total = data.size();
        // 逐状态统计，用于计算解决率和展现服务积压。
        long pending = data.stream().filter(t -> STATUS_PENDING.equals(t.status)).count();
        long inProgress = data.stream().filter(t -> STATUS_IN_PROGRESS.equals(t.status)).count();
        long resolved = data.stream().filter(t -> STATUS_RESOLVED.equals(t.status)).count();
        long closed = data.stream().filter(t -> STATUS_CLOSED.equals(t.status)).count();
        long urgent = data.stream().filter(t -> "紧急".equals(t.priority)).count();
        long high = data.stream().filter(t -> "高".equals(t.priority)).count();

        StringBuilder sb = new StringBuilder();
        sb.append("【客户工单汇总概览】\n\n");

        List<String> filters = new ArrayList<>();
        if (region != null) filters.add("地区: " + region);
        if (status != null) filters.add("状态: " + status);
        if (priority != null) filters.add("优先级: " + priority);
        if (product != null) filters.add("产品: " + product);
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");

        sb.append(String.format("工单总数: %d 个\n\n", total));
        sb.append("【状态分布】\n");
        sb.append(String.format("  待处理: %d 个\n", pending));
        sb.append(String.format("  处理中: %d 个\n", inProgress));
        sb.append(String.format("  已解决: %d 个\n", resolved));
        sb.append(String.format("  已关闭: %d 个\n\n", closed));

        // 已解决与已关闭都视为已完成，用二者之和计算解决率。
        if (total > 0) {
            double resolveRate = (resolved + closed) * 100.0 / total;
            sb.append(String.format("解决率: %.1f%%\n", resolveRate));
        }

        // 紧急和高优先级工单单独提示，便于模型在回答中强调风险。
        if (urgent + high > 0) {
            sb.append(String.format("\n⚠ 紧急/高优先级工单: %d 个（紧急 %d，高 %d）\n", urgent + high, urgent, high));
        }

        Map<String, Long> byProduct = data.stream()
                .collect(Collectors.groupingBy(t -> t.product, Collectors.counting()));
        if (product == null && !byProduct.isEmpty()) {
            sb.append("\n【按产品分布】\n");
            byProduct.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));
        }

        Map<String, Long> byRegion = data.stream()
                .collect(Collectors.groupingBy(t -> t.region, Collectors.counting()));
        if (region == null && !byRegion.isEmpty()) {
            sb.append("\n【按地区分布】\n");
            byRegion.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));
        }

        return sb.toString().trim();
    }

    /** 按优先级优先、创建时间倒序列出最需要处理的工单。 */
    private String buildListResult(List<TicketRecord> data, int limit) {
        // PRIORITIES 的索引已按紧急到低排序，索引越小优先级越高。
        List<TicketRecord> sorted = data.stream()
                .sorted((a, b) -> {
                    int pa = PRIORITIES.indexOf(a.priority);
                    int pb = PRIORITIES.indexOf(b.priority);
                    if (pa != pb) return Integer.compare(pa, pb);
                    return b.createDate.compareTo(a.createDate);
                })
                .limit(limit)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【工单列表】共 %d 条，显示 %d 条（按优先级排序）\n\n", data.size(), sorted.size()));

        for (int i = 0; i < sorted.size(); i++) {
            TicketRecord t = sorted.get(i);
            sb.append(String.format("%d. [%s] %s\n", i + 1, t.ticketId, t.title));
            sb.append(String.format("   客户: %s | 产品: %s | 地区: %s\n", t.customer, t.product, t.region));
            sb.append(String.format("   优先级: %s | 状态: %s | 分类: %s\n", t.priority, t.status, t.category));
            sb.append(String.format("   处理人: %s | 创建时间: %s\n\n", t.engineer, t.createDate));
        }

        return sb.toString().trim();
    }

    /** 从问题分类、产品解决率和处理中工单量三个角度输出统计分析。 */
    private String buildStatsResult(List<TicketRecord> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工单统计分析】\n\n");

        if (data.isEmpty()) {
            sb.append("暂无工单数据");
            return sb.toString();
        }

        // 分类占比帮助识别最常见的技术支持问题。
        Map<String, Long> byCategory = data.stream()
                .collect(Collectors.groupingBy(t -> t.category, Collectors.counting()));
        sb.append("【问题分类统计】\n");
        byCategory.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %s: %d 个 (%.1f%%)\n",
                        e.getKey(), e.getValue(), e.getValue() * 100.0 / data.size())));

        sb.append("\n【各产品解决率】\n");
        // 每个产品分别计算完成占比，避免总解决率掩盖某产品的服务问题。
        Map<String, List<TicketRecord>> byProduct = data.stream()
                .collect(Collectors.groupingBy(t -> t.product));
        byProduct.forEach((product, tickets) -> {
            long resolvedCount = tickets.stream()
                    .filter(t -> STATUS_RESOLVED.equals(t.status) || STATUS_CLOSED.equals(t.status)).count();
            sb.append(String.format("  %s: %.1f%% (%d/%d)\n",
                    product, resolvedCount * 100.0 / tickets.size(), resolvedCount, tickets.size()));
        });

        sb.append("\n【处理人工单量排名】\n");
        // 只统计待处理和处理中工单，反映当前工程师待办负载而非历史总量。
        Map<String, Long> byEngineer = data.stream()
                .filter(t -> STATUS_PENDING.equals(t.status) || STATUS_IN_PROGRESS.equals(t.status))
                .collect(Collectors.groupingBy(t -> t.engineer, Collectors.counting()));
        byEngineer.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append(String.format("  %s: %d 个待处理\n", e.getKey(), e.getValue())));

        return sb.toString().trim();
    }

    /** 使用 AND 语义叠加所有非空条件；客户名允许包含匹配。 */
    private List<TicketRecord> filterData(List<TicketRecord> data, String region, String status,
                                          String priority, String product, String customerName) {
        return data.stream()
                .filter(t -> region == null || region.equals(t.region))
                .filter(t -> status == null || status.equals(t.status))
                .filter(t -> priority == null || priority.equals(t.priority))
                .filter(t -> product == null || product.equals(t.product))
                .filter(t -> customerName == null || t.customer.contains(customerName))
                .toList();
    }

    /** 以当前日期做缓存键，让同一天的多次查询共享同一批模拟工单。 */
    private List<TicketRecord> getOrGenerateData() {
        String key = "tickets_" + LocalDate.now();
        if (cachedData != null && key.equals(cacheKey)) return cachedData;
        cachedData = generateMockData();
        cacheKey = key;
        return cachedData;
    }

    /**
     * 生成最近 30 个自然日的工作日工单。
     * 越早的工单越倾向于关闭或解决，越新的工单更可能仍在排队或处理中，模拟真实生命周期。
     */
    private List<TicketRecord> generateMockData() {
        List<TicketRecord> records = new ArrayList<>();
        LocalDate today = LocalDate.now();
        // 日期固定种子使同一天生成的工单字段与统计值稳定可复现。
        Random random = new Random(today.toEpochDay());
        int ticketSeq = 1;

        // 仅在工作日生成工单，更贴近企业技术支持工作节奏。
        for (int d = 0; d < 30; d++) {
            LocalDate date = today.minusDays(d);
            if (date.getDayOfWeek().getValue() > 5) continue;
            int ticketsPerDay = 2 + random.nextInt(5);

            for (int i = 0; i < ticketsPerDay; i++) {
                TicketRecord ticket = new TicketRecord();
                ticket.ticketId = String.format("TK-%s-%04d", today.format(DateTimeFormatter.ofPattern("yyyyMM")), ticketSeq++);
                ticket.region = REGIONS.get(random.nextInt(REGIONS.size()));
                ticket.customer = CUSTOMERS_BY_REGION.get(ticket.region).get(random.nextInt(4));
                ticket.product = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
                ticket.title = ISSUE_TEMPLATES.get(random.nextInt(ISSUE_TEMPLATES.size()));
                ticket.category = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
                ticket.engineer = ENGINEERS_BY_REGION.get(ticket.region).get(random.nextInt(2));
                ticket.createDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

                // 使用加权分布让“低/中”优先级占多数，“紧急”更少见。
                int priorityWeight = random.nextInt(100);
                if (priorityWeight < 5) ticket.priority = "紧急";
                else if (priorityWeight < 20) ticket.priority = "高";
                else if (priorityWeight < 60) ticket.priority = "中";
                else ticket.priority = "低";

                // 工单越久，处理完成概率越高；新工单则保留较多待处理状态。
                if (d > 7) {
                    ticket.status = random.nextInt(100) < 80 ? STATUS_CLOSED : STATUS_RESOLVED;
                } else if (d > 3) {
                    int statusWeight = random.nextInt(100);
                    if (statusWeight < 30) ticket.status = STATUS_RESOLVED;
                    else if (statusWeight < 60) ticket.status = STATUS_CLOSED;
                    else if (statusWeight < 85) ticket.status = STATUS_IN_PROGRESS;
                    else ticket.status = STATUS_PENDING;
                } else {
                    int statusWeight = random.nextInt(100);
                    if (statusWeight < 35) ticket.status = STATUS_PENDING;
                    else if (statusWeight < 70) ticket.status = STATUS_IN_PROGRESS;
                    else if (statusWeight < 90) ticket.status = STATUS_RESOLVED;
                    else ticket.status = STATUS_CLOSED;
                }

                records.add(ticket);
            }
        }
        return records;
    }

    /** 从 MCP 动态参数读取字符串，不存在时返回 null。 */
    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    /** 从 MCP 动态参数读取整数，仅接受 JSON 数字类型。 */
    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    /** 构造成功的 MCP 文本结果。 */
    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    /** 构造协议级错误结果，供调用端识别工具调用失败。 */
    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    /** 工单模拟数据的内部载体，不直接暴露到 MCP 协议。 */
    private static class TicketRecord {
        String ticketId;
        String region;
        String customer;
        String product;
        String title;
        String category;
        String priority;
        String status;
        String engineer;
        String createDate;
    }
}
