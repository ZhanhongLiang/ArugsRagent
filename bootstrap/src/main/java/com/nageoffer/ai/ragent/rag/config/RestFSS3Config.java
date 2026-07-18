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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * RustFS/MinIO 等 S3 兼容对象存储的客户端装配。
 *
 * 服务端读写和预签名 URL 分别使用 {@link S3Client}、{@link S3Presigner}；
 * 两者必须使用同一 endpoint、凭证和 path-style 设置，否则生成的 URL 无法被兼容服务识别。
 */
@Configuration
public class RestFSS3Config {

    @Bean
    public S3Client s3Client(@Value("${rustfs.url}") String rustfsUrl,
                             @Value("${rustfs.access-key-id}") String accessKeyId,
                             @Value("${rustfs.secret-access-key}") String secretAccessKey) {
        // endpointOverride 指向自托管 S3 兼容服务，而非 AWS 公网端点。
        return S3Client.builder()
                .endpointOverride(URI.create(rustfsUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                // 兼容服务通常以 /bucket/key 识别请求，不能依赖 AWS 的虚拟主机桶名形式。
                .forcePathStyle(true)
                .build();
    }

    /**
     * S3 预签名器，用于生成短期带签名 URL；签名在 query 参数中完成，客户端可直连对象存储传输文件。
     */
    @Bean
    public S3Presigner s3Presigner(@Value("${rustfs.url}") String rustfsUrl,
                                   @Value("${rustfs.access-key-id}") String accessKeyId,
                                   @Value("${rustfs.secret-access-key}") String secretAccessKey) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(rustfsUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                // Presigner 同样必须开启路径风格，保证签名中的请求路径和实际请求一致。
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
