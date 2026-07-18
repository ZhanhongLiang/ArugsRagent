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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 清居商城订单的只读快照，供订单与物流工具共同使用。
 *
 * @param orderNo 订单唯一编号
 * @param phoneTail 下单人手机号后四位，仅用于可选校验
 * @param status 订单整体状态
 * @param paymentStatus 支付状态
 * @param afterSalesStatus 售后状态
 * @param totalAmount 根据订单行计算出的订单金额
 * @param createdAt 下单时间
 * @param items 订单商品明细
 * @param logistics 物流快照；未发货等状态可为 null
 */
public record QingjuOrder(String orderNo,
                          String phoneTail,
                          String status,
                          String paymentStatus,
                          String afterSalesStatus,
                          BigDecimal totalAmount,
                          LocalDateTime createdAt,
                          List<QingjuOrderItem> items,
                          QingjuLogistics logistics) {
}
