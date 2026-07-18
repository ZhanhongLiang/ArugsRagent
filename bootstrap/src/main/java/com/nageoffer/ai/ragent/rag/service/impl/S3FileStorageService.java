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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.util.FileTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

    // AWS SDK S3 客户端：用于 getObject、deleteObject、可靠上传 reliableUpload。
    private final S3Client s3Client;
    // S3 预签名器：用于生成 PUT 预签名 URL，再用 JDK HttpURLConnection 低内存上传。
    private final S3Presigner s3Presigner;

    // Tika 用于探测上传文件的 Content-Type。
    private static final Tika TIKA = new Tika();
    // 预签名 URL 有效期，上传必须在这个时间窗口内完成。
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(10);
    // HTTP 连接超时时间。
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // HTTP 读取超时时间。
    private static final int READ_TIMEOUT_MS = 60_000;

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        // 校验 bucketName，避免生成非法 S3 请求。
        validateBucketName(bucketName);
        // 上传文件不能为空，也不能是空文件。
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        // 读取原始文件名，用于保留后缀和返回给前端展示。
        String originalFilename = file.getOriginalFilename();
        // MultipartFile 已知大小，后续 fixedLengthStreamingMode 必须使用这个 size。
        long size = file.getSize();

        // TIKA 只读流的前几 KB 来探测类型，不会加载整个文件
        // 先单独打开一次输入流给 Tika 探测 Content-Type。
        String detectedContentType;
        try (InputStream is = file.getInputStream()) {
            // Tika 会结合文件头和文件名推断内容类型。
            detectedContentType = TIKA.detect(is, originalFilename);
        }

        // MultipartFile.getInputStream() 每次调用都返回新流（从底层临时文件重新打开），无需再创建临时文件
        // 第二次打开输入流用于真正上传，避免 Tika 探测消耗掉上传流。
        try (InputStream is = file.getInputStream()) {
            // 走低内存预签名 URL 流式上传。
            return streamUploadToS3(bucketName, is, size, originalFilename, detectedContentType);
        }
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType) {
        // 校验 bucketName。
        validateBucketName(bucketName);
        // 调用方必须提供可读输入流。
        Assert.notNull(content, "上传内容不能为空");
        // size 不能为负数；0 会在上层业务决定是否允许。
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        // 如果调用方传了 contentType 就使用；否则根据文件名尝试推断。
        String detected = resolveContentType(originalFilename, contentType);
        // 使用预签名 URL 做低内存流式上传。
        return streamUploadToS3(bucketName, content, size, originalFilename, detected);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType) {
        // 校验 bucketName。
        validateBucketName(bucketName);
        // byte[] 不能为空。
        Assert.notNull(content, "上传内容不能为空");
        // 解析最终 Content-Type。
        String detected = resolveContentType(originalFilename, contentType);
        // byte[] 本身已在内存中，ByteArrayInputStream 不产生额外拷贝
        // 把内存字节包装成输入流，复用同一套流式上传逻辑。
        return streamUploadToS3(bucketName, new ByteArrayInputStream(content), content.length, originalFilename, detected);
    }

    @Override
    public InputStream openStream(String url) {
        // 将 s3://bucket/key 解析成 bucket 和 key。
        S3Location loc = parseS3Url(url);
        // 调用 S3 getObject，返回对象内容输入流。
        return s3Client.getObject(b -> b.bucket(loc.bucket()).key(loc.key()));
    }

    @Override
    @SneakyThrows
    public void deleteByUrl(String url) {
        // 将 s3://bucket/key 解析成 bucket 和 key。
        S3Location loc = parseS3Url(url);
        // 调用 S3 deleteObject 删除对象。
        s3Client.deleteObject(b -> b.bucket(loc.bucket()).key(loc.key()));
    }

    /**
     * 通过 S3Presigner 生成预签名 URL，配合 HttpURLConnection 流式上传
     * <p>
     * 为什么不用 SDK 的 putObject / S3TransferManager？
     * AWS SDK v2 (截至 2.40.x) 的所有同步/异步上传 API 都会在 SigV4 签名管线中
     * 将 payload 缓冲到堆内存（即使使用 RequestBody.fromFile）
     * <p>
     * 预签名 URL 将鉴权信息编码到 URL 查询参数中，payload 使用 UNSIGNED-PAYLOAD，
     * 不需要预读内容计算 SHA-256。HttpURLConnection.setFixedLengthStreamingMode
     * 保证请求体流式发送，全程堆内存占用仅为内部 buffer 大小
     */
    @SneakyThrows
    private StoredFileDTO streamUploadToS3(String bucketName, InputStream inputStream,
                                           long size, String originalFilename,
                                           String detectedContentType) {
        // 为本次上传生成唯一对象 key，保留原文件后缀。
        String s3Key = generateS3Key(originalFilename);

        // 1. 生成预签名 URL（纯 CPU 计算，无 IO）
        // presignPutObject 会把 bucket/key/contentType 等信息编码到签名 URL 和签名请求头中。
        // 这个是预签名的生成过程 URL
        PresignedPutObjectRequest presignedReq = s3Presigner.presignPutObject(p -> p
                .signatureDuration(PRESIGN_DURATION)
                .putObjectRequest(PutObjectRequest.builder()
                        // 目标 bucket。
                        .bucket(bucketName)
                        // 目标对象 key。
                        .key(s3Key)
                        // 对象 Content-Type，便于后续下载或预览。
                        .contentType(detectedContentType)
                        .build()));

        // 2. 流式上传
        // 使用 JDK HttpURLConnection 直接 PUT 到预签名 URL，避免 SDK 上传管线缓冲 payload。
        streamPutViaPresignedUrl(presignedReq, inputStream, size, detectedContentType);

        // 3. 构建返回结果
        // 系统内部保存 s3://bucket/key 形式的地址，而不是临时预签名 URL。
        String url = toS3Url(bucketName, s3Key);
        // 返回统一 StoredFileDTO，供知识库文档表保存。
        return buildStoredFileDTO(url, originalFilename, detectedContentType, size);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO reliableUpload(String bucketName, InputStream content, long size,
                                        String originalFilename, String contentType) {
        // 校验 bucketName。
        validateBucketName(bucketName);
        // 校验输入流。
        Assert.notNull(content, "上传内容不能为空");
        // 校验大小。
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        // 确定 Content-Type。
        String detected = resolveContentType(originalFilename, contentType);

        // 生成对象 key。
        String s3Key = generateS3Key(originalFilename);

        // 使用 AWS SDK 原生 putObject，优点是 SDK 自带重试，缺点是可能增加堆内存占用。
        s3Client.putObject(
                PutObjectRequest.builder()
                        // 目标 bucket。
                        .bucket(bucketName)
                        // 目标对象 key。
                        .key(s3Key)
                        // 对象 Content-Type。
                        .contentType(detected)
                        .build(),
                // SDK 需要知道输入流长度。
                RequestBody.fromInputStream(content, size));

        // 构造内部存储 URL。
        String url = toS3Url(bucketName, s3Key);
        // 构造返回 DTO。
        return buildStoredFileDTO(url, originalFilename, detected, size);
    }

    /**
     * 使用预签名 URL 执行 HTTP PUT 流式上传
     */
    private void streamPutViaPresignedUrl(PresignedPutObjectRequest presignedReq,
                                          InputStream inputStream,
                                          long size,
                                          String contentType) throws IOException {
        // 根据预签名 URL 打开 HTTP 连接。
        HttpURLConnection conn = (HttpURLConnection) presignedReq.url().openConnection();
        try {
            // PUT 请求需要写请求体。
            conn.setDoOutput(true);
            // S3 上传对象使用 HTTP PUT。
            conn.setRequestMethod("PUT");
            // 固定长度流式上传：JDK 不会把整个请求体缓存到内存。
            conn.setFixedLengthStreamingMode(size);
            // 设置连接超时。
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            // 设置读取响应超时。
            conn.setReadTimeout(READ_TIMEOUT_MS);

            // 预签名请求可能要求携带特定签名头，必须原样写入连接。
            presignedReq.signedHeaders()
                    .forEach((k, vs) -> vs.forEach(v -> conn.addRequestProperty(k, v)));

            // 如果有 Content-Type，也写入 HTTP 请求头；要与预签名时的 Content-Type 保持一致。
            if (contentType != null && !contentType.isBlank()) {
                conn.setRequestProperty("Content-Type", contentType);
            }

            // 打开请求输出流并把输入流内容传输过去。
            try (OutputStream out = conn.getOutputStream()) {
                // transferTo 会循环读 inputStream 并写入 out，不需要手写 buffer。
                inputStream.transferTo(out);
            }

            // 读取 S3 响应码。
            int code = conn.getResponseCode();
            // 2xx 才表示上传成功。
            if (code < 200 || code >= 300) {
                // 失败时读取错误响应体，便于定位签名、权限、bucket、key 等问题。
                String errorBody = readErrorStream(conn);
                // 抛 IOException，让上层 @SneakyThrows 继续向外传播。
                throw new IOException(String.format(
                        "S3 流式上传失败: HTTP %d, url=%s, body=%s",
                        code, presignedReq.url(), errorBody));
            }
        } finally {
            // 释放底层 HTTP 连接。
            conn.disconnect();
        }
    }

    private String readErrorStream(HttpURLConnection conn) {
        // 读取 HTTP 错误响应体。
        try (InputStream err = conn.getErrorStream()) {
            // 有错误流则按 UTF-8 转成字符串；没有则返回固定空标记。
            return err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "(empty)";
        } catch (IOException e) {
            // 连错误流都读取失败时，返回读取失败原因，不再抛出二次异常。
            return "(read error: " + e.getMessage() + ")";
        }
    }

    private String toS3Url(String bucket, String key) {
        // 系统内部统一使用 s3://bucket/key 作为对象存储地址。
        return "s3://" + bucket + "/" + key;
    }

    private S3Location parseS3Url(String url) {
        // 解析 s3://bucket/key 字符串。
        URI uri = URI.create(url);
        // 只支持 s3 scheme，避免误删/误读非对象存储地址。
        if (!"s3".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported url scheme: " + url);
        }
        // host 部分就是 bucket。
        String bucket = uri.getHost();
        // path 部分是 /key。
        String path = uri.getPath();
        // bucket 不能为空。
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Invalid s3 url (bucket missing): " + url);
        }
        // 去掉 path 开头的 /，得到真正的 S3 key。
        String key = (path != null && path.startsWith("/")) ? path.substring(1) : path;
        // key 不能为空。
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Invalid s3 url (key missing): " + url);
        }
        // 返回解析后的 bucket/key。
        return new S3Location(bucket, key);
    }

    // S3 地址解析结果。
    private record S3Location(String bucket, String key) {
    }

    private String extractSuffix(String filename) {
        // 文件名为空时没有后缀。
        if (filename == null) return "";
        // 找最后一个点号。
        int idx = filename.lastIndexOf('.');
        // 没有点号或点号在末尾时，认为没有可用后缀。
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    private String generateS3Key(String originalFilename) {
        // 从原始文件名中提取后缀。
        String suffix = extractSuffix(originalFilename);
        // 使用 UUID 生成随机 key，避免文件名冲突和暴露原始文件名。
        UUID uuid = UUID.randomUUID();
        // 把 UUID 的高低位格式化为 32 位十六进制字符串。
        String key = String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        // 有后缀则保留后缀，便于对象存储或下载侧识别文件类型。
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private void validateBucketName(String bucketName) {
        // bucketName 是对象存储写入的必要参数，不能为空。
        Assert.notBlank(bucketName, "bucketName 不能为空");
    }

    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                             String contentType, long size) {
        // 根据文件名和 Content-Type 进一步归类出业务文件类型，如 pdf/doc/txt 等。
        String detectedType = FileTypeDetector.detectType(originalFilename, contentType);
        // 构造统一文件存储 DTO，供文档表保存和后续解析使用。
        return StoredFileDTO.builder()
                // 内部存储地址。
                .url(url)
                // 业务识别出的文件类型。
                .detectedType(detectedType)
                // 文件大小。
                .size(size)
                // 原始文件名。
                .originalFilename(originalFilename)
                .build();
    }

    private String resolveContentType(String originalFilename, String contentType) {
        // 调用方已经传入 Content-Type 时优先使用。
        if (contentType != null && !contentType.isBlank()) return contentType;
        // 否则根据文件名让 Tika 做轻量推断。
        if (originalFilename != null && !originalFilename.isBlank()) return TIKA.detect(originalFilename);
        // 文件名和 Content-Type 都没有时，只能返回 null。
        return null;
    }
}
