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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP/HTTPS 文档抓取策略。
 *
 * <p>将 DocumentSource 中的 credentials 转为请求头；下载成功后优先采用响应 Content-Type，
 * 缺失时再按字节和文件名推断 MIME 类型，保证解析节点拿到稳定输入。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpUrlFetcher implements DocumentFetcher {

    /** 统一处理超时、重定向、大小限制与响应头解析的 HTTP 辅助组件。 */
    private final HttpClientHelper httpClientHelper;

    @Override
    /** @return URL 来源类型，使 FetcherNode 能将 URL 文档路由到本策略。 */
    public SourceType supportedType() {
        return SourceType.URL;
    }

    @Override
    /** 下载 URL 内容、解析文件名与 MIME 类型，并返回原始字节。 */
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("链接地址不能为空");
        }

        // token 这类简写凭证会在 buildHeaders 中转为标准 Authorization 请求头。
        Map<String, String> headers = buildHeaders(source.getCredentials());
        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = normalizeContentType(resp.contentType());
        // 部分静态服务器不返回 Content-Type，回退到内容与扩展名探测。
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 将来源凭证映射为 HTTP 请求头。
     * key=token 是项目定义的快捷写法，会转换为 Bearer；其他键值保持原样，支持自定义 Cookie 等认证方式。
     */
    private Map<String, String> buildHeaders(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        credentials.forEach((k, v) -> {
            if (!StringUtils.hasText(k) || v == null) {
                return;
            }
            if ("token".equalsIgnoreCase(k)) {
                headers.put("Authorization", "Bearer " + v);
            } else {
                headers.put(k, v);
            }
        });
        return headers;
    }

    /** 去掉 Content-Type 中的 charset 等参数，只保留用于解析器选择的 MIME 主类型。 */
    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        int idx = contentType.indexOf(';');
        return idx > 0 ? contentType.substring(0, idx).trim() : contentType.trim();
    }
}
