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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文件存储服务抽象。
 *
 * <p>业务层只依赖这个接口，不直接依赖 S3/RustFS/MinIO SDK。
 * 这样后续如果把对象存储从 S3 兼容服务换成别的实现，只需要替换实现类。</p>
 */
public interface FileStorageService {

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     *
     * @param bucketName 目标 bucket 名称
     * @param file Spring MVC 接收到的 multipart 文件
     * @return 存储后的文件元信息，包括 s3:// 地址、文件类型、大小、原始文件名
     */
    StoredFileDTO upload(String bucketName, MultipartFile file);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     *
     * @param bucketName 目标 bucket 名称
     * @param content 文件输入流，调用方负责提供可读流
     * @param size 文件大小，预签名流式上传需要固定长度
     * @param originalFilename 原始文件名，用于生成后缀和返回展示
     * @param contentType 内容类型，可为空；为空时实现类会尝试推断
     * @return 存储后的文件元信息
     */
    StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     *
     * @param bucketName 目标 bucket 名称
     * @param content 已经在内存中的文件字节
     * @param originalFilename 原始文件名
     * @param contentType 内容类型
     * @return 存储后的文件元信息
     */
    StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType);

    /**
     * 上传文件（SDK 原生，带自动重试）
     * <p>
     * 通过 AWS SDK 的 putObject 上传，具备 SDK 内置的自动重试机制（网络抖动、超时等场景自动重发）
     * 代价是 SDK 上传管线会将 payload 缓冲到堆内存（实测 30MB 文件约 100MB 堆增量）
     * 适用于小文件上传或对重试可靠性要求高、但对内存不敏感的场景。
     *
     * @param bucketName 目标 bucket 名称
     * @param content 文件输入流
     * @param size 文件大小
     * @param originalFilename 原始文件名
     * @param contentType 内容类型
     * @return 存储后的文件元信息
     */
    StoredFileDTO reliableUpload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 根据存储 URL 打开文件读取流。
     *
     * @param url 文件存储地址，当前实现使用 s3://bucket/key
     * @return 文件内容输入流，调用方使用完需要关闭
     */
    InputStream openStream(String url);

    /**
     * 根据存储 URL 删除文件。
     *
     * @param url 文件存储地址，当前实现使用 s3://bucket/key
     */
    void deleteByUrl(String url);
}
