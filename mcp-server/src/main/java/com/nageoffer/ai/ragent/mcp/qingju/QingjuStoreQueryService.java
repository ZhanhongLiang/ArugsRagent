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
import java.util.Optional;

/**
 * 清居商城数据查询端口。
 *
 * <p>工具层依赖此接口而非数据库或 HTTP 客户端实现，以便演示数据、真实商城接口和测试替身可以互换。</p>
 */
public interface QingjuStoreQueryService {

    /**
     * 查询订单；提供手机号后四位时必须同时匹配。
     *
     * @param orderNo 订单号
     * @param phoneTail 可选的手机号后四位
     * @return 匹配到的订单，查询失败或校验失败时为空
     */
    Optional<QingjuOrder> findOrder(String orderNo, String phoneTail);

    /** @return 指定 SKU 的商品；SKU 缺失或不存在时为空。 */
    Optional<QingjuProduct> findProduct(String sku);

    /** @return 指定主机型号是否在该配件声明的适配型号范围中。 */
    boolean isAccessoryCompatible(String productModel, String accessorySku);

    /** @return 可供浏览或导入的全部 SKU 商品。 */
    List<QingjuProduct> listProducts();
}
