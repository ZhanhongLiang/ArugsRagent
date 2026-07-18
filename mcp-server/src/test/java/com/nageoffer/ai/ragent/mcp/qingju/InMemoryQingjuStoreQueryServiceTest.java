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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryQingjuStoreQueryServiceTest {

    private final InMemoryQingjuStoreQueryService service = new InMemoryQingjuStoreQueryService();

    @Test
    void shouldProvideFiftyProductsAcrossFiveCategories() {
        assertThat(service.listProducts()).hasSize(50)
                .extracting(QingjuProduct::category)
                .contains("无线清洁", "空气护理", "个护美发", "耗材配件", "礼盒与延保");
    }

    @Test
    void shouldFindOrderAndValidateOptionalPhoneTail() {
        assertThat(service.findOrder("QJ20260713008", "7788")).isPresent();
        assertThat(service.findOrder("QJ20260713008", "0000")).isEmpty();
    }

    @Test
    void shouldResolveAccessoryCompatibility() {
        assertThat(service.isAccessoryCompatible("VC-A2", "QJ-ACC-002")).isTrue();
        assertThat(service.isAccessoryCompatible("AP-A2", "QJ-ACC-002")).isFalse();
    }
}
