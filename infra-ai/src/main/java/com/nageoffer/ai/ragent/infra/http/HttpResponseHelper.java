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

package com.nageoffer.ai.ragent.infra.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.NoArgsConstructor;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 模型 HTTP 客户端共用的响应读取、JSON 解析和配置前置校验工具。
 *
 * <p>把这些防御性逻辑集中后，各供应商客户端只处理自己的协议字段，且异常可被路由执行器统一识别并 fallback。</p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class HttpResponseHelper {

    /** 可复用的 JSON 解析器，响应解析不携带状态。 */
    private static final Gson GSON = new Gson();

    /** 读取响应体原始文本；body 缺失时返回空串，便于拼接 HTTP 错误日志。 */
    public static String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        // 以 UTF-8 显式解码，避免供应商错误体中的中文在日志中乱码。
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    /**
     * 将响应体解析为 JsonObject。
     *
     * @param body  OkHttp 响应体
     * @param label 提供商标签，用于异常消息
     * @return 解析后的 JsonObject
     */
    public static JsonObject parseJson(ResponseBody body, String label) throws IOException {
        if (body == null) {
            throw new ModelClientException(label + " 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // ResponseBody 只能读取一次，调用方不得在本方法后再次读取它。
        String content = body.string();
        return GSON.fromJson(content, JsonObject.class);
    }

    /** 校验当前目标已绑定 ProviderConfig，否则客户端没有 URL、端点等连接信息。 */
    public static AIModelProperties.ProviderConfig requireProvider(ModelTarget target, String label) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException(label + " 提供商配置缺失");
        }
        return target.provider();
    }

    /** 校验需要认证的云供应商已配置 API Key，避免发送必然失败的网络请求。 */
    public static void requireApiKey(AIModelProperties.ProviderConfig provider, String label) {
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException(label + " API密钥缺失");
        }
    }

    /** 校验候选模型名称存在，并返回要写入供应商请求体的 model 值。 */
    public static String requireModel(ModelTarget target, String label) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException(label + " 模型名称缺失");
        }
        return target.candidate().getModel();
    }
}
