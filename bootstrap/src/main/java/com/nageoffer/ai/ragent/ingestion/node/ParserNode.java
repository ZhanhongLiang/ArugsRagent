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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ParserSettings;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParseResult;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 将抓取到的原始字节转换为文本和结构化元数据的解析节点。
 *
 * <p>节点先做管道配置的文件类型白名单校验，再选择 Tika 解析器；解析结果同时保留 rawText 与 StructuredDocument，
 * 使后续增强、切片和元数据写入使用同一份事实来源。</p>
 */
@Component
public class ParserNode implements IngestionNode {

    /** 将节点 JSON 配置反序列化为 ParserSettings。 */
    private final ObjectMapper objectMapper;
    /** 根据解析器类型查找具体实现，当前由 Tika 处理多格式文件。 */
    private final DocumentParserSelector parserSelector;

    /** 注入配置转换器与解析器选择器。 */
    public ParserNode(ObjectMapper objectMapper, DocumentParserSelector parserSelector) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
    }

    /** @return 管道配置中 parser 节点对应的稳定类型值。 */
    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    /**
     * 探测 MIME、校验白名单、选择解析器并把文本与元数据写入上下文。
     * 文件名优先参与类型判断，因为部分 HTTP/S3 来源缺失或错误标注 Content-Type。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        // Fetcher 未识别 MIME 时在解析前兜底探测，保证解析器收到明确输入。
        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // 先按当前管道配置限制允许格式，避免错误文件进入高成本 Tika 解析。
        validateMimeType(settings, mimeType, fileName);

        // 匹配到的规则可携带解析选项；未匹配但无白名单时使用空选项。
        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);
        DocumentParser parser = parserSelector.select(ParserType.TIKA.getType());
        if (parser == null) {
            return NodeResult.fail(new ClientException("未配置 Tika 解析器"));
        }

        // Tika 协议统一接收字节、MIME 与可选解析参数。
        Map<String, Object> options = rule == null ? Collections.emptyMap() : rule.getOptions();
        ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);
        context.setRawText(result.text());

        // 保留文本和解析元数据，后续节点无需再次调用解析器。
        StructuredDocument document = StructuredDocument.builder()
                .text(result.text())
                .metadata(result.metadata())
                .build();
        context.setDocument(document);

        return NodeResult.ok("解析文本长度=" + (result.text() == null ? 0 : result.text().length()));
    }

    /**
     * 校验文件是否命中管道配置的解析规则。
     * 没有规则时表示不限制类型；有规则时必须命中类型或 ALL，防止用户上传未允许格式。
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            // 没有配置规则，允许所有类型，由 Tika 自身决定是否可解析。
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        // 规则可写 PDF、pdf、.pdf 等形式，统一归一化后再比较。
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            // 报错中带出允许类型，方便前端或运营人员修正管道配置。
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    /** 将节点 JSON 设置转换为强类型配置；空设置等价于“无类型限制”。 */
    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    /** 返回首个匹配规则，以读取该规则的解析 options。 */
    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 将文件名和 MIME 归一为管道规则使用的类型名。
     * 文件扩展名优先，因为部分供应商返回泛化的 application/octet-stream。
     */
    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    /** 通过常见扩展名快速识别配置层类型；无法判断时交给 MIME 识别。 */
    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    /** 将用户配置的别名（MD、DOCX、* 等）归一为内部比较值。 */
    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
