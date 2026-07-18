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

package com.nageoffer.ai.ragent.mcp.amap;

/**
 * 地址地理编码后的标准地址与经纬度。
 *
 * @param formattedAddress 高德标准化后的地址文本
 * @param longitude 经度
 * @param latitude 纬度
 */
public record AmapLocation(String formattedAddress, String longitude, String latitude) {

    /** @return 高德路线接口要求的 {@code 经度,纬度} 坐标格式。 */
    public String coordinate() {
        return longitude + "," + latitude;
    }
}
