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

package com.nageoffer.ai.ragent.knowledge.handler;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 远程文件拉取服务
 * 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteFileFetcher {

    /*
     * 远程导入和定时刷新边界：
     * - HEAD 只是优化手段，很多源站的 Content-Length、ETag、Last-Modified 并不可靠。
     * - 真正的安全兜底是：边下载边限流、边写临时文件、必要时计算 SHA-256。
     * - 发生变更的文件以临时文件形式返回，让上层调度逻辑可以统一完成元数据切换、对象存储上传和重新分块。
     */
    // HTTP 工具类：负责 HEAD、GET、流式打开远程响应。
    private final HttpClientHelper httpClientHelper;
    // 文件存储抽象：负责把最终文件上传到 S3/RustFS/MinIO 等兼容对象存储。
    private final FileStorageService fileStorageService;

    // 复用 Spring multipart 最大文件配置，保证远程导入也遵守同一套文件大小上限。
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /**
     * 流式拉取远程文件并上传到存储（用于文档上传场景）
     */
    public StoredFileDTO fetchAndStore(String bucketName, String url) {
        /*
         * 文档上传场景：
         * 远程文件不会一次性读入内存，而是先按大小限制流式落到临时文件，
         * 再按实际读取到的字节数上传到对象存储，避免盲目信任不准确的 Content-Length。
         */
        // 将 DataSize 配置转换为字节数，后续所有大小判断都用字节比较。
        long maxBytes = maxFileSize.toBytes();
        // 去除 URL 两端空白，避免用户输入或表单传参带来的无意义空格。
        url = url.trim();
        // 先尝试 HEAD 预检，能提前拿到文件大小、文件名、内容类型和缓存校验信息。
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);
        // HEAD 失败时 headResponse 为 null，此时只能等真正下载时再判断大小。
        Long headContentLength = headResponse == null ? null : headResponse.contentLength();
        // 如果 HEAD 已经明确告诉文件超限，就提前拒绝，避免继续下载浪费带宽。
        checkSizeLimit(maxBytes, headContentLength);

        // 打开远程响应流；try-with-resources 保证方法结束时关闭 HTTP Response。
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            // 文件名优先级：GET 响应解析出的文件名 > HEAD 解析出的文件名 > 固定兜底名。
            String fileName = firstHasText(response.fileName(), headResponse == null ? null : headResponse.fileName(), "remote-file");
            // 内容类型优先级：GET 响应 Content-Type > HEAD 响应 Content-Type > null。
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            // 部分源站的 Content-Length/HEAD 响应并不可靠，固定长度上传会在字节数不一致时失败
            // 远程导入统一先落临时文件，以实际读取到的字节数作为上传大小
            // 使用临时文件中转后，再交给 FileStorageService 上传到对象存储。
            return uploadViaTemp(bucketName, response.bodyStream(), fileName, contentType, maxBytes);
        }
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     * 返回的 RemoteFetchResult 实现了 AutoCloseable，调用方必须用 try-with-resources 管理生命周期
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        /*
         * 定时刷新场景：
         * 优先用低成本的 ETag / Last-Modified 判断是否变化；
         * 如果这些响应头缺失或变化，再下载内容并计算 SHA-256。
         * 哈希值是最终依据，因为它对应后续真正会被分块的文件字节。
         */
        // 获取最大允许下载字节数。
        long maxBytes = maxFileSize.toBytes();
        // 规整 URL，避免空格影响请求。
        url = url.trim();
        // 先尝试 HEAD，若源站不支持 HEAD 会在 tryHead 内降级为 null。
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);

        // HEAD 成功时，先用响应头做快速判断。
        if (headResponse != null) {
            // HEAD 中如果已经声明文件过大，直接拒绝。
            checkSizeLimit(maxBytes, headResponse.contentLength());
            // 清洗 ETag，空字符串统一转为 null。
            String etag = trimOrNull(headResponse.etag());
            // 清洗 Last-Modified，空字符串统一转为 null。
            String headLastModified = trimOrNull(headResponse.lastModified());
            // 如果新的 ETag 与上次保存的 ETag 一致，认为远程内容未变化。
            boolean etagMatch = StringUtils.hasText(etag) && etag.equals(trimOrNull(lastEtag));
            // 如果新的 Last-Modified 与上次保存的一致，也可认为远程内容未变化。
            boolean modifiedMatch = StringUtils.hasText(headLastModified) && headLastModified.equals(trimOrNull(lastModified));
            // 任一强校验/弱校验命中即可跳过下载，减少远程刷新成本。
            if (etagMatch || modifiedMatch) {
                // skipped 表示未变化；不返回临时文件，调用方无需后续上传和分块。
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        // 定时刷新需要把下载结果落到临时文件；后续成功时交给调用方处理，失败时本方法负责清理。
        Path tempFile = null;
        // 打开远程下载流；try-with-resources 保证 HTTP 响应最终关闭。
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            // 创建调度刷新专用临时文件，避免直接覆盖旧文件。
            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            // 边下载边写入临时文件，同时计算 SHA-256，并持续检查大小上限。
            CopyResult copyResult = copyWithLimitAndDigest(response.bodyStream(), tempFile, maxBytes);
            // 空文件对知识库没有意义，直接作为客户端输入问题拒绝。
            if (copyResult.size == 0) {
                // 抛异常前先清理已经创建的空临时文件。
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            // 下载得到的真实内容哈希。
            String hash = copyResult.sha256Hex;
            // ETag 优先取 GET 响应，其次取 HEAD 响应。
            String etag = firstHasText(trimOrNull(response.etag()), headResponse == null ? null : trimOrNull(headResponse.etag()), null);
            // Last-Modified 优先取 GET 响应，其次取 HEAD 响应。
            String fetchLastModified = firstHasText(trimOrNull(response.lastModified()), headResponse == null ? null : trimOrNull(headResponse.lastModified()), null);

            // 如果内容哈希与上次一致，说明文件字节未变化，即使响应头变化也可以跳过。
            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                // 未变化时临时文件不再需要，立即删除。
                deleteTempFileQuietly(tempFile);
                // 返回 skipped，并带上最新 ETag/Last-Modified/hash 供上层记录。
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            // 文件名优先取远程响应解析结果，取不到时使用调用方传入的兜底文件名。
            String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : fallbackFileName;
            // 返回 changed，并把临时文件所有权交给 RemoteFetchResult，调用方必须 close 清理。
            return RemoteFetchResult.changed(tempFile, copyResult.size, response.contentType(), fileName, hash, etag, fetchLastModified);
        } catch (IOException e) {
            // IO 异常时清理临时文件，避免磁盘残留。
            deleteTempFileQuietly(tempFile);
            // 转成业务统一的 ServiceException，向上表达远程拉取失败。
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            // ClientException/ServiceException 等运行时异常也要清理临时文件。
            deleteTempFileQuietly(tempFile);
            // 保留原异常类型和堆栈继续抛出。
            throw e;
        }
    }

    private HttpClientHelper.HttpHeadResponse tryHead(String url) {
        try {
            // 发起 HEAD 请求，尝试获取元信息而不下载正文。
            return httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            // HEAD 失败不代表 GET 失败，降级为直接下载，不阻断远程导入。
            log.debug("HEAD 获取失败，改为直接下载: {}", url, e);
            // 返回 null 表示没有可用 HEAD 元信息。
            return null;
        }
    }

    private void checkSizeLimit(long maxBytes, Long contentLength) {
        // maxBytes <= 0 表示不限制；contentLength 为 null 表示源站没有提供大小，暂时无法预判。
        if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
            // 已知远程文件超过限制时直接拒绝，避免继续下载。
            throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
        }
    }

    private StoredFileDTO uploadViaTemp(String bucketName, InputStream remoteStream, String fileName,
                                        String contentType, long maxBytes) {
        // 临时文件路径，finally 中会统一清理。
        Path tempFile = null;
        try {
            // 创建上传中转临时文件，避免把远程文件全部读进堆内存。
            tempFile = Files.createTempFile("knowledge-upload-", ".tmp");
            // 边读远程流边写临时文件，同时检查实际字节数是否超过限制。
            long size = copyWithLimit(remoteStream, tempFile, maxBytes);
            // 空文件不能进入知识库。
            if (size == 0) {
                throw new ClientException("远程文件内容为空");
            }
            // 重新打开临时文件输入流，用实际 size 调用对象存储上传。
            try (InputStream tempInputStream = Files.newInputStream(tempFile)) {
                // 上传成功后返回对象存储地址、文件类型、大小等信息。
                return fileStorageService.upload(bucketName, tempInputStream, size, fileName, contentType);
            }
        } catch (IOException e) {
            // 临时文件创建、读取或上传流打开失败时，统一转成服务异常。
            throw new ServiceException("远程文件上传失败: " + e.getMessage());
        } finally {
            // 不管上传成功还是失败，只要创建过临时文件都要尽力删除。
            if (tempFile != null) {
                try {
                    // deleteIfExists 避免文件已被删除时再次报错。
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // 删除失败不影响主流程结果，只记录警告，交给系统临时目录后续清理。
                    log.warn("删除远程上传临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        // 记录已经复制的总字节数。
        long total = 0;
        // 打开临时文件输出流；try-with-resources 保证写入结束后关闭文件句柄。
        try (var outputStream = Files.newOutputStream(tempFile)) {
            // 8KB 缓冲区，避免逐字节读写造成性能问题。
            byte[] buffer = new byte[8192];
            // 本次读取的字节数。
            int len;
            // 循环读取远程输入流，直到 EOF。
            while ((len = inputStream.read(buffer)) != -1) {
                // 累加实际读取字节数。
                total += len;
                // 每读一段都检查上限，防止源站 Content-Length 不准确导致超大文件落盘。
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                }
                // 将本次读取的有效字节写入临时文件。
                outputStream.write(buffer, 0, len);
            }
            // 返回真实复制字节数，后续对象存储上传需要这个大小。
            return total;
        }
    }

    private CopyResult copyWithLimitAndDigest(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        /*
         * 一次读取，同时得到两个结果：
         * 字节流写入临时文件的同时，用同一批字节更新 SHA-256。
         * 这样既能保持内存稳定，也能保证 hash 与后续真正入库、分块的文件完全一致。
         */
        try {
            // 获取 SHA-256 摘要计算器。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 记录总读取字节数。
            long total = 0;
            // 打开临时文件输出流。
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                // 8KB 缓冲区用于流式复制。
                byte[] buffer = new byte[8192];
                // 本次读取长度。
                int len;
                // 循环读取远程流。
                while ((len = inputStream.read(buffer)) != -1) {
                    // 累加实际读取字节数。
                    total += len;
                    // 按真实读取量进行大小限制检查。
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                    }
                    // 写入临时文件。
                    outputStream.write(buffer, 0, len);
                    // 用同一段字节更新 SHA-256 摘要。
                    digest.update(buffer, 0, len);
                }
            }
            // 输出总大小和十六进制哈希。
            return new CopyResult(total, hexEncode(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            // 理论上 JDK 必带 SHA-256；如果不可用，说明运行环境异常。
            throw new ServiceException("SHA-256 算法不可用");
        }
    }

    // copyWithLimitAndDigest 的轻量返回对象：文件大小 + SHA-256 十六进制字符串。
    private record CopyResult(long size, String sha256Hex) {
    }

    private static String hexEncode(byte[] hash) {
        // 一个字节转两个十六进制字符，所以容量预估为 hash.length * 2。
        StringBuilder hex = new StringBuilder(hash.length * 2);
        // 逐字节转换。
        for (byte b : hash) {
            // 0xff & b 用于把 signed byte 转成 0~255 的无符号值。
            String value = Integer.toHexString(0xff & b);
            // 单位数十六进制要补 0，保证每个字节固定两个字符。
            if (value.length() == 1) {
                hex.append('0');
            }
            // 追加当前字节的十六进制表示。
            hex.append(value);
        }
        // 返回完整十六进制字符串。
        return hex.toString();
    }

    private void deleteTempFileQuietly(Path tempFile) {
        // 只有临时文件路径存在时才尝试删除。
        if (tempFile != null) {
            try {
                // 文件不存在时不抛异常。
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // 清理失败不能覆盖主异常，只记录日志。
                log.warn("删除临时文件失败: {}", tempFile, e);
            }
        }
    }

    private String firstHasText(String... values) {
        // 按传入顺序寻找第一个非空文本。
        for (String v : values) {
            // hasText 会排除 null、空字符串和纯空白字符串。
            if (StringUtils.hasText(v)) return v;
        }
        // 全部为空时返回 null。
        return null;
    }

    private String trimOrNull(String value) {
        // 有文本时返回 trim 后结果；无文本统一返回 null，方便后续比较。
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static final class RemoteFetchResult implements AutoCloseable {

        // true 表示远程内容发生变化，需要调用方继续上传/分块；false 表示可跳过。
        private final boolean changed;
        // 变更文件对应的临时文件；skipped 时为 null。
        private Path tempFile;
        // 实际下载字节数。
        private final long size;
        // 远程响应 Content-Type。
        private final String contentType;
        // 解析出的文件名。
        private final String fileName;
        // 内容 SHA-256，用于后续刷新判重。
        private final String contentHash;
        // 远程 ETag。
        private final String etag;
        // 远程 Last-Modified。
        private final String lastModified;
        // skipped 或异常说明。
        private final String message;

        private RemoteFetchResult(boolean changed, Path tempFile, long size, String contentType,
                                  String fileName, String contentHash, String etag,
                                  String lastModified, String message) {
            // 记录是否变化。
            this.changed = changed;
            // 保存临时文件路径，changed=true 时 close() 负责清理。
            this.tempFile = tempFile;
            // 保存实际文件大小。
            this.size = size;
            // 保存内容类型。
            this.contentType = contentType;
            // 保存文件名。
            this.fileName = fileName;
            // 保存内容哈希。
            this.contentHash = contentHash;
            // 保存 ETag。
            this.etag = etag;
            // 保存 Last-Modified。
            this.lastModified = lastModified;
            // 保存提示消息。
            this.message = message;
        }

        public static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            // 未变化结果没有临时文件、大小、文件名和内容类型，只保留校验信息和消息。
            return new RemoteFetchResult(false, null, 0, null, null, contentHash, etag, lastModified, message);
        }

        public static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                                 String contentHash, String etag, String lastModified) {
            // 变化结果携带临时文件，由调用方处理并在 finally/try-with-resources 中 close。
            return new RemoteFetchResult(true, tempFile, size, contentType, fileName, contentHash, etag, lastModified, null);
        }

        // 是否发生变化。
        public boolean changed() { return changed; }
        // 变更内容对应的临时文件路径。
        public Path tempFile() { return tempFile; }
        // 实际文件大小。
        public long size() { return size; }
        // 内容类型。
        public String contentType() { return contentType; }
        // 文件名。
        public String fileName() { return fileName; }
        // 内容哈希。
        public String contentHash() { return contentHash; }
        // ETag。
        public String etag() { return etag; }
        // Last-Modified。
        public String lastModified() { return lastModified; }
        // 提示消息。
        public String message() { return message; }

        @Override
        public void close() {
            // 只有持有临时文件时才需要释放资源。
            if (tempFile != null) {
                try {
                    // 删除 changed 结果持有的临时文件。
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // 尽力清理：close 阶段不再抛异常，避免覆盖主流程异常。
                }
                // 删除后置空，避免重复 close 时重复删除。
                tempFile = null;
            }
        }
    }
}
