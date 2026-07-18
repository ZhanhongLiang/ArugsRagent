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

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * 清居演示商城的内存数据实现。
 *
 * <p>它用于展示 MCP 调用链，不依赖真实电商平台或数据库。未来接入 ShopXO、ERP 或订单中心时，
 * 只需新增 {@link QingjuStoreQueryService} 的实现，工具协议层无需修改。</p>
 */
@Service
public class InMemoryQingjuStoreQueryService implements QingjuStoreQueryService {

    /** 启动时生成的不可变 SKU 列表，覆盖主机、耗材和延保等展示场景。 */
    private final List<QingjuProduct> products = buildProducts();
    /** SKU 大小写无关索引，避免模型输出小写编码导致本可命中的查询失败。 */
    private final Map<String, QingjuProduct> productsBySku = products.stream()
            .collect(Collectors.toUnmodifiableMap(product -> product.sku().toUpperCase(Locale.ROOT), product -> product));
    /** 订单号大小写无关索引；订单构造依赖 productsBySku，因此放在商品索引之后初始化。 */
    private final Map<String, QingjuOrder> ordersByNo = buildOrders().stream()
            .collect(Collectors.toUnmodifiableMap(order -> order.orderNo().toUpperCase(Locale.ROOT), order -> order));

    /**
     * 按订单号查订单，并在传入手机号后四位时执行二次校验。
     * 不区分“不存在”和“校验失败”，避免工具响应泄露订单是否真实存在。
     */
    @Override
    public Optional<QingjuOrder> findOrder(String orderNo, String phoneTail) {
        if (isBlank(orderNo)) {
            return Optional.empty();
        }
        QingjuOrder order = ordersByNo.get(orderNo.trim().toUpperCase(Locale.ROOT));
        if (order == null || (!isBlank(phoneTail) && !order.phoneTail().equals(phoneTail.trim()))) {
            return Optional.empty();
        }
        return Optional.of(order);
    }

    /** 按 SKU 查询商品；统一 trim 与大写后再访问索引。 */
    @Override
    public Optional<QingjuProduct> findProduct(String sku) {
        if (isBlank(sku)) {
            return Optional.empty();
        }
        return Optional.ofNullable(productsBySku.get(sku.trim().toUpperCase(Locale.ROOT)));
    }

    /** 根据配件商品维护的 compatibleModels 判断适配关系，主机型号比较时忽略大小写。 */
    @Override
    public boolean isAccessoryCompatible(String productModel, String accessorySku) {
        if (isBlank(productModel) || isBlank(accessorySku)) {
            return false;
        }
        return findProduct(accessorySku)
                .map(product -> product.compatibleModels().stream()
                        .anyMatch(model -> model.equalsIgnoreCase(productModel.trim())))
                .orElse(false);
    }

    /** 返回不可变的完整商品目录，供商品浏览或批量导入场景使用。 */
    @Override
    public List<QingjuProduct> listProducts() {
        return products;
    }

    /** 批量生成演示 SKU，使测试数据既足够丰富又保留稳定、可识别的编号规则。 */
    private List<QingjuProduct> buildProducts() {
        List<QingjuProduct> result = new ArrayList<>();
        addSeries(result, "QJ-VC-", "VC-A", "清居灵吸无线清洁机", "无线清洁", 10, 1899, 20, index -> List.of());
        addSeries(result, "QJ-AIR-", "AP-A", "清居澄风空气护理机", "空气护理", 8, 1599, 18, index -> List.of());
        addSeries(result, "QJ-CARE-", "HC-A", "清居柔风个护造型器", "个护美发", 8, 899, 16, index -> List.of());

        // 无线清洁机型号池；后续耗材按序号切分为不同适配区间。
        List<String> vacuumModels = List.of("VC-A1", "VC-A2", "VC-A3", "VC-A4", "VC-A5", "VC-A6", "VC-A7", "VC-A8", "VC-A9", "VC-A10");
        addSeries(result, "QJ-ACC-", "ACC-", "清居耗材配件", "耗材配件", 18, 79, 12,
                index -> switch (index) {
                    case 1, 2, 3 -> vacuumModels.subList(0, 4);
                    case 4, 5, 6 -> vacuumModels.subList(4, 8);
                    case 7, 8 -> vacuumModels.subList(8, 10);
                    case 9, 10, 11, 12 -> List.of("AP-A1", "AP-A2", "AP-A3", "AP-A4");
                    case 13, 14, 15 -> List.of("AP-A5", "AP-A6", "AP-A7", "AP-A8");
                    default -> List.of("HC-A1", "HC-A2", "HC-A3", "HC-A4", "HC-A5", "HC-A6", "HC-A7", "HC-A8");
                });
        addSeries(result, "QJ-GIFT-", "GIFT-", "清居延保礼盒", "礼盒与延保", 6, 199, 50, index -> List.of());
        return List.copyOf(result);
    }

    /**
     * 按统一规则追加一个商品系列。
     * compatibleModels 是序号到适配型号列表的函数，使不同配件 SKU 可拥有不同兼容范围。
     */
    private void addSeries(List<QingjuProduct> products,
                           String skuPrefix,
                           String modelPrefix,
                           String namePrefix,
                           String category,
                           int count,
                           int basePrice,
                           int baseStock,
                           IntFunction<List<String>> compatibleModels) {
        // 使用三位序号让 SKU 在测试数据、日志和提示词中保持稳定且容易识别。
        for (int index = 1; index <= count; index++) {
            String sequence = String.format("%03d", index);
            products.add(new QingjuProduct(
                    skuPrefix + sequence,
                    modelPrefix + index,
                    namePrefix + " " + index + " 号",
                    category,
                    BigDecimal.valueOf(basePrice + (long) (index - 1) * 100L),
                    baseStock + index * 3,
                    compatibleModels.apply(index)
            ));
        }
    }

    /** 生成覆盖待付款、待发货、在途、签收、退款、部分发货等状态的演示订单。 */
    private List<QingjuOrder> buildOrders() {
        return List.of(
                order("QJ20260713001", "1024", "待发货", "已支付", "无售后", "2026-07-13 09:16", "QJ-VC-001", "QJ-ACC-001", null),
                order("QJ20260713002", "3348", "已发货", "已支付", "无售后", "2026-07-13 10:25", "QJ-VC-002", "QJ-ACC-002", logistics("顺丰速运", "SF20260713002", "运输中", "2026-07-14", "2026-07-13 12:10 商品已由深圳仓发出", "2026-07-13 20:40 已到达广州转运中心")),
                order("QJ20260713003", "6671", "已签收", "已支付", "无售后", "2026-07-12 14:08", "QJ-AIR-003", null, logistics("京东物流", "JD20260713003", "已签收", "已签收", "2026-07-12 16:30 商品已由上海仓发出", "2026-07-13 11:18 已签收，感谢选择清居智能")),
                order("QJ20260713004", "8890", "已取消", "已退款", "退款完成", "2026-07-11 15:36", "QJ-CARE-001", null, null),
                order("QJ20260713005", "2918", "售后处理中", "已支付", "退货退款审核中", "2026-07-10 09:55", "QJ-VC-005", "QJ-ACC-005", logistics("顺丰速运", "SF20260713005", "已签收", "已签收", "2026-07-10 12:30 商品已由杭州仓发出", "2026-07-11 10:08 已签收")),
                order("QJ20260713006", "4432", "部分发货", "已支付", "无售后", "2026-07-09 16:20", "QJ-AIR-006", "QJ-ACC-012", logistics("中通快递", "ZT20260713006", "部分发货", "2026-07-15", "2026-07-10 09:10 主商品已由武汉仓发出", "滤芯补发包裹等待出库")),
                order("QJ20260713007", "5567", "已发货", "已支付", "无售后", "2026-07-08 11:46", "QJ-CARE-007", "QJ-ACC-016", logistics("京东物流", "JD20260713007", "运输中", "2026-07-14", "2026-07-08 14:05 商品已由北京仓发出", "2026-07-09 06:25 已到达天津分拣中心")),
                order("QJ20260713008", "7788", "已发货", "已支付", "无售后", "2026-07-07 19:30", "QJ-VC-008", "QJ-GIFT-001", logistics("顺丰速运", "SF20260713008", "运输中", "2026-07-14", "2026-07-08 08:42 商品已由广州仓发出", "2026-07-09 17:30 快件正在派送")),
                order("QJ20260713009", "9012", "待付款", "待支付", "无售后", "2026-07-07 08:10", "QJ-AIR-008", null, null),
                order("QJ20260713010", "1357", "已签收", "已支付", "换货已完成", "2026-07-06 13:00", "QJ-VC-010", "QJ-ACC-008", logistics("顺丰速运", "SF20260713010", "已签收", "已签收", "2026-07-06 16:50 商品已由成都仓发出", "2026-07-07 15:20 已签收")),
                order("QJ20260713011", "2468", "待发货", "已支付", "无售后", "2026-07-05 20:18", "QJ-CARE-004", "QJ-ACC-017", null),
                order("QJ20260713012", "3579", "已签收", "已支付", "无售后", "2026-07-04 10:05", "QJ-AIR-001", "QJ-ACC-009", logistics("京东物流", "JD20260713012", "已签收", "已签收", "2026-07-04 12:25 商品已由南京仓发出", "2026-07-05 09:40 已签收"))
        );
    }

    /**
     * 根据 SKU 构造订单行、实时汇总金额，再将文本时间转换为 LocalDateTime。
     * 金额不硬编码，调整商品价格后订单总价仍能与行项目保持一致。
     */
    private QingjuOrder order(String orderNo,
                               String phoneTail,
                               String status,
                               String paymentStatus,
                               String afterSalesStatus,
                               String createdAt,
                               String primarySku,
                               String accessorySku,
                               QingjuLogistics logistics) {
        // 主商品必有，配件则按订单场景可选地追加。
        List<QingjuOrderItem> items = new ArrayList<>();
        addOrderItem(items, primarySku);
        if (accessorySku != null) {
            addOrderItem(items, accessorySku);
        }
        // 按“单价 × 数量”汇总，避免模拟订单金额与商品数据脱节。
        BigDecimal totalAmount = items.stream()
                .map(item -> productsBySku.get(item.sku()).price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new QingjuOrder(orderNo, phoneTail, status, paymentStatus, afterSalesStatus, totalAmount,
                LocalDateTime.parse(createdAt.replace(" ", "T")), List.copyOf(items), logistics);
    }

    /** 从商品索引读取 SKU 对应的展示名称，生成数量为 1 的订单行。 */
    private void addOrderItem(List<QingjuOrderItem> items, String sku) {
        QingjuProduct product = productsBySku.get(sku);
        items.add(new QingjuOrderItem(product.sku(), product.name(), 1));
    }

    /** 用可变数量的物流轨迹快速构造一个订单物流快照。 */
    private QingjuLogistics logistics(String carrier,
                                      String trackingNo,
                                      String fulfillmentStatus,
                                      String estimatedDelivery,
                                      String... timeline) {
        return new QingjuLogistics(carrier, trackingNo, fulfillmentStatus, estimatedDelivery, List.of(timeline));
    }

    /** 内部入参判空工具，防止空白请求参与索引查询。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
