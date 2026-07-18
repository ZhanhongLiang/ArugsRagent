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

import java.util.List;

/**
 * 订单履约物流快照。
 *
 * @param carrier 承运商名称
 * @param trackingNo 承运商运单号
 * @param fulfillmentStatus 当前仓配履约状态
 * @param estimatedDelivery 预计送达日期或已签收标记
 * @param timeline 按时间先后排列的物流节点文本
 */
public record QingjuLogistics(String carrier,
                              String trackingNo,
                              String fulfillmentStatus,
                              String estimatedDelivery,
                              List<String> timeline) {
}
