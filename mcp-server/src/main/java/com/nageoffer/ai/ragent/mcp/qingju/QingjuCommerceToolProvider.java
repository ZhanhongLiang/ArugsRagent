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

package com.nageoffer.ai.ragent.mcp.qingju;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清居智能电商场景的 MCP 工具提供者。
 *
 * <p>Spring 启动时会把本类声明的 {@link McpServerFeatures.SyncToolSpecification} 收集到 MCP Server。
 * Bootstrap 端通过 {@code tools/list} 发现这些工具，再由意图树将订单、物流、库存和配件兼容性问题路由到这里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QingjuCommerceToolProvider {

    /** 订单查询工具的稳定标识，需与意图树中配置的 mcpToolId 对应。 */
    private static final String ORDER_QUERY_TOOL = "qingju_order_query";
    /** 物流查询工具的稳定标识。 */
    private static final String LOGISTICS_QUERY_TOOL = "qingju_logistics_query";
    /** SKU 库存查询工具的稳定标识。 */
    private static final String INVENTORY_QUERY_TOOL = "qingju_inventory_query";
    /** 主机与耗材配件兼容性查询工具的稳定标识。 */
    private static final String COMPATIBILITY_QUERY_TOOL = "qingju_accessory_compatibility";
    /** MCP 文本结果中的下单时间展示格式，不影响内部 LocalDateTime 的存储。 */
    private static final DateTimeFormatter ORDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 工具层只负责协议转换和结果呈现，具体商城数据由该接口解耦。 */
    private final QingjuStoreQueryService storeQueryService;

    /** 注册订单查询工具及其同步处理器。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification qingjuOrderQueryToolSpecification() {
        return toolSpecification(orderTool(), this::handleOrderQuery);
    }

    /** 注册物流查询工具及其同步处理器。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification qingjuLogisticsQueryToolSpecification() {
        return toolSpecification(logisticsTool(), this::handleLogisticsQuery);
    }

    /** 注册 SKU 库存查询工具及其同步处理器。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification qingjuInventoryQueryToolSpecification() {
        return toolSpecification(inventoryTool(), this::handleInventoryQuery);
    }

    /** 注册主机与配件兼容性查询工具及其同步处理器。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification qingjuAccessoryCompatibilityToolSpecification() {
        return toolSpecification(compatibilityTool(), this::handleCompatibilityQuery);
    }

    /** 将工具元数据与本地处理函数组装为 MCP 可执行规格；本场景不需要读取 exchange 上下文。 */
    private McpServerFeatures.SyncToolSpecification toolSpecification(Tool tool, ToolHandler handler) {
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> handler.handle(request));
    }

    /** 定义订单工具的名称、给模型看的用途描述以及 JSON 参数 Schema。 */
    private Tool orderTool() {
        return tool(ORDER_QUERY_TOOL,
                "查询清居智能模拟商城的订单状态、支付状态、商品明细和售后状态；订单号必填，手机号后四位可用于校验。",
                schema(Map.of(
                        "orderNo", stringProperty("清居订单号，例如 QJ20260713008"),
                        "phoneTail", stringProperty("手机号后四位；如用户提供则用于订单校验")
                ), List.of("orderNo")));
    }

    /** 定义物流工具，复用订单号和可选手机号后四位作为身份校验信息。 */
    private Tool logisticsTool() {
        return tool(LOGISTICS_QUERY_TOOL,
                "查询清居智能模拟商城订单的履约状态、承运商、运单号和物流节点；订单号必填。",
                schema(Map.of(
                        "orderNo", stringProperty("清居订单号，例如 QJ20260713008"),
                        "phoneTail", stringProperty("手机号后四位；如用户提供则用于订单校验")
                ), List.of("orderNo")));
    }

    /** 定义库存工具；SKU 是唯一必填条件，避免模型只传商品俗称导致误查。 */
    private Tool inventoryTool() {
        return tool(INVENTORY_QUERY_TOOL,
                "查询清居智能模拟商城 SKU 的商品名称、型号、售价和当前库存；SKU 必填。",
                schema(Map.of("sku", stringProperty("SKU 编码，例如 QJ-VC-008 或 QJ-ACC-005")), List.of("sku")));
    }

    /** 定义兼容性工具；必须同时知道主机型号和待购买配件 SKU 才能给出结论。 */
    private Tool compatibilityTool() {
        return tool(COMPATIBILITY_QUERY_TOOL,
                "查询清居智能主机型号与耗材配件是否兼容；需提供主机型号和配件 SKU。",
                schema(Map.of(
                        "productModel", stringProperty("主机型号，例如 VC-A2、AP-A3 或 HC-A1"),
                        "accessorySku", stringProperty("配件 SKU，例如 QJ-ACC-002")
                ), List.of("productModel", "accessorySku")));
    }

    /** 执行订单查询，并将领域对象转换为可直接拼入模型上下文的文本结果。 */
    private CallToolResult handleOrderQuery(CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        String orderNo = stringArg(args, "orderNo");
        return storeQueryService.findOrder(orderNo, stringArg(args, "phoneTail"))
                .map(order -> success("【清居智能订单】\n"
                        + "订单号：" + order.orderNo() + "\n"
                        + "订单状态：" + order.status() + "\n"
                        + "支付状态：" + order.paymentStatus() + "\n"
                        + "售后状态：" + order.afterSalesStatus() + "\n"
                        + "下单时间：" + ORDER_TIME_FORMATTER.format(order.createdAt()) + "\n"
                        + "订单商品：" + formatItems(order.items()) + "\n"
                        + "订单金额：¥" + order.totalAmount().setScale(2, RoundingMode.HALF_UP)))
                .orElseGet(() -> error("未查询到订单，请核对订单号；如已提供手机号后四位，也请核对校验信息。"));
    }

    /** 执行物流查询；订单不存在和校验不通过都统一返回安全的未查询到提示。 */
    private CallToolResult handleLogisticsQuery(CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        return storeQueryService.findOrder(stringArg(args, "orderNo"), stringArg(args, "phoneTail"))
                .map(this::formatLogistics)
                .orElseGet(() -> error("未查询到订单，请核对订单号；如已提供手机号后四位，也请核对校验信息。"));
    }

    /** 执行 SKU 查询，返回回答库存和商品规格所需的最小商品信息。 */
    private CallToolResult handleInventoryQuery(CallToolRequest request) {
        String sku = stringArg(arguments(request), "sku");
        return storeQueryService.findProduct(sku)
                .map(product -> success("【清居智能库存】\n"
                        + "商品：" + product.name() + "\n"
                        + "SKU：" + product.sku() + "\n"
                        + "型号：" + product.model() + "\n"
                        + "分类：" + product.category() + "\n"
                        + "售价：¥" + product.price().setScale(2, RoundingMode.HALF_UP) + "\n"
                        + "可售库存：" + product.stock()))
                .orElseGet(() -> error("未找到该 SKU，请提供形如 QJ-VC-008 或 QJ-ACC-005 的商品编码。"));
    }

    /** 先校验主机型号和配件 SKU，再查询配件并计算其适配结论。 */
    private CallToolResult handleCompatibilityQuery(CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        String productModel = stringArg(args, "productModel");
        String accessorySku = stringArg(args, "accessorySku");
        if (isBlank(productModel) || isBlank(accessorySku)) {
            return error("请同时提供主机型号和配件 SKU。\n例如：productModel=VC-A2，accessorySku=QJ-ACC-002");
        }
        return storeQueryService.findProduct(accessorySku)
                .map(accessory -> success("【清居智能配件兼容性】\n"
                        + "主机型号：" + productModel + "\n"
                        + "配件：" + accessory.name() + "（" + accessory.sku() + "）\n"
                        + "兼容结论：" + (storeQueryService.isAccessoryCompatible(productModel, accessorySku) ? "兼容" : "不兼容") + "\n"
                        + "该配件适配型号：" + formatCompatibleModels(accessory.compatibleModels())))
                .orElseGet(() -> error("未找到该配件 SKU，请提供形如 QJ-ACC-002 的配件编码。"));
    }

    /** 未发货订单没有物流对象是正常状态，不应被视为工具调用失败。 */
    private CallToolResult formatLogistics(QingjuOrder order) {
        QingjuLogistics logistics = order.logistics();
        if (logistics == null) {
            return success("【清居智能订单物流】\n订单号：" + order.orderNo() + "\n当前订单状态：" + order.status()
                    + "\n暂未生成物流单号，请等待仓库发货。");
        }
        return success("【清居智能订单物流】\n"
                + "订单号：" + order.orderNo() + "\n"
                + "履约状态：" + logistics.fulfillmentStatus() + "\n"
                + "承运商：" + logistics.carrier() + "\n"
                + "运单号：" + logistics.trackingNo() + "\n"
                + "预计送达：" + logistics.estimatedDelivery() + "\n"
                + "物流节点：\n- " + String.join("\n- ", logistics.timeline()));
    }

    /** 统一通过 MCP SDK 的 Tool 构建器创建工具，避免四个工具的装配方式分叉。 */
    private Tool tool(String name, String description, JsonSchema schema) {
        return Tool.builder().name(name).description(description).inputSchema(schema).build();
    }

    /** 创建 object 类型的 JSON Schema；LinkedHashMap 保留字段展示顺序。 */
    private JsonSchema schema(Map<String, ? extends Object> properties, List<String> required) {
        return new JsonSchema("object", new LinkedHashMap<>(properties), required, null, null, null);
    }

    /** 创建一个字符串字段定义，描述会帮助模型理解参数语义。 */
    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    /** MCP 请求未携带 arguments 时返回空 Map，调用方无需处理 null。 */
    private Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    /** 从动态 JSON 参数读取字符串并去掉首尾空白；缺失时返回 null。 */
    private String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    /** 将订单行项目压缩成适合 MCP 文本响应展示的一行摘要。 */
    private String formatItems(List<QingjuOrderItem> items) {
        return items.stream().map(item -> item.name() + " x" + item.quantity()).reduce((left, right) -> left + "；" + right).orElse("无");
    }

    /** 格式化配件适配型号；空列表明确表示没有已配置的适配范围。 */
    private String formatCompatibleModels(List<String> compatibleModels) {
        return compatibleModels.isEmpty() ? "该商品不是配件或未配置适配范围" : String.join("、", compatibleModels);
    }

    /** 判定模型参数是否缺失或只有空白字符。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 构造成功的 MCP 文本结果，并记录长度以便排查异常大的工具响应。 */
    private CallToolResult success(String text) {
        log.info("MCP 工具调用完成, resultLength={}", text.length());
        return CallToolResult.builder().content(List.of(new TextContent(text))).isError(false).build();
    }

    /** 构造 isError=true 的 MCP 结果，让调用端知道未获得可用业务数据。 */
    private CallToolResult error(String message) {
        return CallToolResult.builder().content(List.of(new TextContent(message))).isError(true).build();
    }

    /** 用函数式接口统一四类工具的请求处理入口。 */
    @FunctionalInterface
    private interface ToolHandler {
        CallToolResult handle(CallToolRequest request);
    }
}
