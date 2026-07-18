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
import java.util.List;

/**
 * 清居商城中的 SKU 商品快照。
 *
 * @param sku 商品库存单位编码，是库存和配件查询的主键
 * @param model 商品型号，用于主机与配件适配判断
 * @param name 面向用户展示的商品名称
 * @param category 商品分类，用于让模型区分主机、耗材和延保
 * @param price 当前演示售价
 * @param stock 当前可售库存
 * @param compatibleModels 配件可适配的主机型号；非配件一般为空列表
 */
public record QingjuProduct(String sku,
                            String model,
                            String name,
                            String category,
                            BigDecimal price,
                            int stock,
                            List<String> compatibleModels) {
}
