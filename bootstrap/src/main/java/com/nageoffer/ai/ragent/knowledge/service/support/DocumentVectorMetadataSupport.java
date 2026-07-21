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

package com.nageoffer.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DocumentVectorMetadataSupport {

    private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private static final Set<String> RESERVED_KEYS = Set.of("collection_name", "doc_id", "chunk_index");

    private final ObjectMapper objectMapper;

    public String normalize(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            if (root == null || !root.isObject()) {
                throw new ClientException("文档元数据必须是 JSON 对象");
            }
            root.fields().forEachRemaining(entry -> validateEntry(entry.getKey(), entry.getValue()));
            return objectMapper.writeValueAsString(root);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("文档元数据 JSON 格式不合法");
        }
    }

    public void attach(String metadataJson, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || !StringUtils.hasText(metadataJson)) {
            return;
        }
        Map<String, Object> documentMetadata = toMetadata(metadataJson);
        for (VectorChunk chunk : chunks) {
            Map<String, Object> merged = new HashMap<>();
            if (chunk.getMetadata() != null) {
                merged.putAll(chunk.getMetadata());
            }
            merged.putAll(documentMetadata);
            chunk.setMetadata(merged);
        }
    }

    private Map<String, Object> toMetadata(String metadataJson) {
        String normalized = normalize(metadataJson);
        if (normalized == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(normalized, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("文档元数据 JSON 格式不合法");
        }
    }

    private void validateEntry(String key, JsonNode value) {
        if (!METADATA_KEY.matcher(key).matches()) {
            throw new ClientException("文档元数据字段名不合法: " + key);
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new ClientException("文档元数据不能覆盖系统字段: " + key);
        }
        if (value == null || value.isNull() || value.isContainerNode() && !value.isArray()) {
            throw new ClientException("文档元数据只支持标量或标量数组: " + key);
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                throw new ClientException("文档元数据数组不能为空: " + key);
            }
            for (JsonNode item : value) {
                if (!item.isValueNode() || item.isNull()) {
                    throw new ClientException("文档元数据数组只支持标量: " + key);
                }
            }
        }
    }
}
