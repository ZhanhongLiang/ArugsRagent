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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class MetadataFilterSupport {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> RESERVED_KEYS = Set.of("collection_name", "doc_id", "chunk_index");
    private static final int MAX_VALUES_PER_KEY = 64;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MetadataFilterSupport() {
    }

    static void appendPgConditions(StringBuilder sql, List<Object> parameters, Map<String, Object> filters) {
        for (Filter filter : normalize(filters)) {
            sql.append(" AND (");
            for (int index = 0; index < filter.values().size(); index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                Object value = filter.values().get(index);
                sql.append("(metadata->>? = ? OR metadata @> jsonb_build_object(?::text, ?::jsonb))");
                parameters.add(filter.key());
                parameters.add(String.valueOf(value));
                parameters.add(filter.key());
                parameters.add(asSingleValueArrayJson(value));
            }
            sql.append(')');
        }
    }

    static String toMilvusExpression(Map<String, Object> filters) {
        List<String> conditions = new ArrayList<>();
        for (Filter filter : normalize(filters)) {
            List<String> values = new ArrayList<>();
            for (Object value : filter.values()) {
                String literal = toMilvusLiteral(value);
                String field = "metadata[\"" + filter.key() + "\"]";
                values.add("(" + field + " == " + literal + " OR json_contains(" + field + ", " + literal + "))");
            }
            conditions.add("(" + String.join(" OR ", values) + ")");
        }
        return String.join(" AND ", conditions);
    }

    private static List<Filter> normalize(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<Filter> normalized = new ArrayList<>(filters.size());
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            validateKey(entry.getKey());
            List<Object> values = valuesOf(entry.getKey(), entry.getValue());
            normalized.add(new Filter(entry.getKey(), values));
        }
        return normalized;
    }

    private static List<Object> valuesOf(String key, Object value) {
        List<Object> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            values.addAll(collection);
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(value, index));
            }
        } else {
            values.add(value);
        }
        if (values.isEmpty() || values.size() > MAX_VALUES_PER_KEY) {
            throw new ClientException("检索元数据过滤值数量不合法: " + key);
        }
        values.forEach(item -> validateValue(key, item));
        return values;
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new ClientException("检索元数据字段名不合法: " + key);
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new ClientException("检索元数据不能覆盖系统字段: " + key);
        }
    }

    private static void validateValue(String key, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new ClientException("检索元数据过滤值只支持字符串、数字或布尔值: " + key);
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue.floatValue())) {
            throw new ClientException("检索元数据过滤数值不合法: " + key);
        }
    }

    private static String asSingleValueArrayJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(List.of(value));
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化检索元数据过滤值", e);
        }
    }

    private static String toMilvusLiteral(Object value) {
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        return String.valueOf(value);
    }

    private record Filter(String key, List<Object> values) {
    }
}
