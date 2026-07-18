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

package com.nageoffer.ai.ragent.ingestion.util;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求工具类，用于获取网络资源
 */
@Component
@RequiredArgsConstructor
public class HttpClientHelper {

    // 同步 OkHttpClient：用于普通 GET、HEAD 和远程文件流式下载。
    @Qualifier("syncHttpClient")
    private final OkHttpClient client;

    public HttpFetchResponse get(String url, Map<String, String> headers) {
        // 无大小限制的普通 GET：会把响应体完整读入 byte[]，适合小文件或普通接口响应。
        return doGet(url, headers, -1);
    }

    public HttpFetchResponse getWithLimit(String url, Map<String, String> headers, long maxBytes) {
        // 带大小限制的普通 GET：仍然返回 byte[]，但读取过程中超过 maxBytes 会中断。
        return doGet(url, headers, maxBytes);
    }

    public HttpFetchStream openStream(String url, Map<String, String> headers, long maxBytes) {
        // 构造 GET 请求。
        Request.Builder builder = new Request.Builder().url(url);
        // 透传调用方自定义请求头，例如鉴权、User-Agent、条件请求头等。
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try {
            // 这里不能用 try-with-resources，因为响应体流要返回给调用方继续读取。
            Response response = client.newCall(builder.get().build()).execute();
            // HTTP 非 2xx 统一视为远程请求失败。
            if (!response.isSuccessful()) {
                // 尽量读取错误响应体，方便日志和异常定位。
                String body = response.body() != null ? response.body().string() : "";
                // openStream 返回前如果失败，必须主动关闭 response，避免连接泄漏。
                response.close();
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            // 获取响应体对象，后面会转成 InputStream。
            ResponseBody responseBody = response.body();
            // 读取内容类型，用于后续文件类型识别或对象存储 Content-Type。
            String contentType = response.header("Content-Type");
            // 读取 Content-Disposition，常用于解析远程文件名。
            String disposition = response.header("Content-Disposition");
            // 优先从 Content-Disposition 解析文件名，取不到则从 URL path 解析。
            String fileName = resolveFileName(disposition, url);
            // 读取 ETag，用于定时刷新变更检测。
            String etag = response.header("ETag");
            // 读取 Last-Modified，用于定时刷新变更检测。
            String lastModified = response.header("Last-Modified");
            // 解析 Content-Length，解析失败时返回 null。
            Long contentLength = parseContentLength(response.header("Content-Length"));
            // 如果远程明确声明 Content-Length 已超过限制，则直接拒绝。
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                // 拒绝前关闭 response，避免连接占用。
                response.close();
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }
            // 如果没有响应体，返回空流；否则对响应体流包一层大小限制。
            InputStream bodyStream = responseBody == null
                    ? InputStream.nullInputStream()
                    : wrapWithLimit(responseBody.byteStream(), maxBytes);
            // 把 response 和 bodyStream 一起交给调用方；调用方 close() 时会关闭底层 response。
            return new HttpFetchStream(response, bodyStream, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            // 网络异常、连接失败、读响应头失败等都统一转成 ServiceException。
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    private HttpFetchResponse doGet(String url, Map<String, String> headers, long maxBytes) {
        // 构造 GET 请求。
        Request.Builder builder = new Request.Builder().url(url);
        // 添加调用方传入的请求头。
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        // 普通 GET 在本方法内完整读取响应体，所以可以用 try-with-resources 自动关闭 response。
        try (Response response = client.newCall(builder.get().build()).execute()) {
            // 非成功状态码转业务异常。
            if (!response.isSuccessful()) {
                // 尽量携带错误响应体，便于排查远程服务返回内容。
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            // 提取响应元信息。
            String contentType = response.header("Content-Type");
            // Content-Disposition 可能包含 filename。
            String disposition = response.header("Content-Disposition");
            // 解析文件名。
            String fileName = resolveFileName(disposition, url);
            // 读取 ETag。
            String etag = response.header("ETag");
            // 读取 Last-Modified。
            String lastModified = response.header("Last-Modified");
            // 读取并解析 Content-Length。
            Long contentLength = parseContentLength(response.header("Content-Length"));
            // 如果响应头已明确超限，提前失败。
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }

            // 最终响应体字节数组。
            byte[] bytes;
            // 没有响应体时返回空数组，避免调用方空指针。
            if (response.body() == null) {
                bytes = new byte[0];
            // 有大小限制时，边读边检查上限。
            } else if (maxBytes > 0) {
                bytes = readWithLimit(response.body().byteStream(), maxBytes);
            // 无大小限制时，直接交给 OkHttp 读取全部响应体。
            } else {
                bytes = response.body().bytes();
            }
            // 返回完整响应体和响应元信息。
            return new HttpFetchResponse(bytes, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            // 统一包装 IO 异常。
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    public HttpHeadResponse head(String url, Map<String, String> headers) {
        // 构造 HEAD 请求。HEAD 只取响应头，不下载正文。
        Request.Builder builder = new Request.Builder().url(url);
        // 添加调用方传入的请求头。
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        // HEAD 响应在本方法内消费完，自动关闭。
        try (Response response = client.newCall(builder.head().build()).execute()) {
            // HEAD 不成功时抛异常；上层可选择降级为 GET。
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            // 读取 Content-Type。
            String contentType = response.header("Content-Type");
            // 读取 Content-Disposition。
            String disposition = response.header("Content-Disposition");
            // 解析文件名。
            String fileName = resolveFileName(disposition, url);
            // 读取 ETag。
            String etag = response.header("ETag");
            // 读取 Last-Modified。
            String lastModified = response.header("Last-Modified");
            // 解析 Content-Length。
            Long contentLength = parseContentLength(response.header("Content-Length"));
            // 返回 HEAD 元信息。
            return new HttpHeadResponse(etag, lastModified, contentType, contentLength, fileName);
        } catch (IOException e) {
            // 包装网络异常。
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    private String resolveFileName(String disposition, String url) {
        // 优先从 Content-Disposition 中解析 filename。
        if (disposition != null) {
            // Content-Disposition 通常形如 attachment; filename="a.pdf"。
            String[] parts = disposition.split(";");
            // 遍历每个分号分隔的参数。
            for (String part : parts) {
                // 去掉参数两侧空格。
                String trimmed = part.trim();
                // 只处理 filename= 参数。
                if (trimmed.startsWith("filename=")) {
                    // 截取 filename= 后面的原始值。
                    String raw = trimmed.substring("filename=".length()).trim();
                    // 去掉包裹的双引号。
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    // URL 解码后返回文件名。
                    return decode(raw);
                }
            }
        }
        try {
            // 如果响应头没有文件名，则尝试从 URL path 末尾解析。
            URL parsed = new URL(url);
            // 获取 URL path 部分。
            String path = parsed.getPath();
            // path 为空时无法解析文件名。
            if (path == null || path.isBlank()) {
                return null;
            }
            // 找到最后一个 /。
            int idx = path.lastIndexOf('/');
            // 返回最后一段路径；没有 / 时返回整个 path。
            return idx >= 0 ? path.substring(idx + 1) : path;
        } catch (Exception e) {
            // URL 非法或解析失败时，不影响主流程，返回 null。
            return null;
        }
    }

    private String decode(String value) {
        try {
            // 按 UTF-8 解码文件名中的百分号编码。
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解码失败时返回原始值，避免因为文件名问题中断下载。
            return value;
        }
    }

    private Long parseContentLength(String header) {
        // 没有 Content-Length 时返回 null。
        if (header == null) {
            return null;
        }
        try {
            // Content-Length 正常应为长整数字符串。
            return Long.parseLong(header);
        } catch (NumberFormatException ignore) {
            // 源站返回非法 Content-Length 时忽略，后续靠实际读取限制兜底。
            return null;
        }
    }

    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        // 普通 GET 需要把响应体读成 byte[]，这里用 ByteArrayOutputStream 聚合。
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 8KB 缓冲区，平衡内存和 IO 次数。
            byte[] buffer = new byte[8192];
            // 已读取总字节数。
            long total = 0;
            // 本次读取字节数。
            int len;
            // 循环读取直到 EOF。
            while ((len = in.read(buffer)) != -1) {
                // 累加真实读取字节数。
                total += len;
                // 每次读取后检查是否超过上限。
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
                // 写入内存输出流。
                out.write(buffer, 0, len);
            }
            // 返回完整字节数组。
            return out.toByteArray();
        }
    }

    private InputStream wrapWithLimit(InputStream inputStream, long maxBytes) {
        // maxBytes <= 0 表示不限制，直接返回原始流。
        if (maxBytes <= 0) {
            return inputStream;
        }
        // 返回一个装饰器 InputStream：读取行为不变，但每次读取后累计字节数并检查上限。
        return new InputStream() {
            // 当前已经从底层流读取的总字节数。
            private long total;

            @Override
            public int read() throws IOException {
                // 读取单个字节。
                int value = inputStream.read();
                // value == -1 表示 EOF，不计入大小。
                if (value != -1) {
                    // 单字节读取时增量为 1。
                    ensureWithinLimit(1);
                }
                // 返回底层读取结果。
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                // 批量读取字节。
                int count = inputStream.read(b, off, len);
                // count > 0 表示本次确实读取到了数据。
                if (count > 0) {
                    // 按本次读取数量累计并校验。
                    ensureWithinLimit(count);
                }
                // 返回底层读取数量。
                return count;
            }

            @Override
            public void close() throws IOException {
                // 关闭包装流时关闭底层响应体流。
                inputStream.close();
            }

            private void ensureWithinLimit(int delta) {
                // 累加本次读取增量。
                total += delta;
                // 超过限制时抛业务异常，中断调用方继续读取。
                if (total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
            }
        };
    }

    // 一次性 GET 响应：body 已完整读入内存，同时携带响应元信息。
    public record HttpFetchResponse(byte[] body,
                                    String contentType,
                                    String fileName,
                                    String etag,
                                    String lastModified,
                                    Long contentLength) {
    }

    // 流式 GET 响应：bodyStream 由调用方读取，close() 时负责关闭底层 HTTP Response。
    public record HttpFetchStream(Response response,
                                  InputStream bodyStream,
                                  String contentType,
                                  String fileName,
                                  String etag,
                                  String lastModified,
                                  Long contentLength) implements AutoCloseable {

        @Override
        public void close() {
            // 关闭 OkHttp Response 会释放连接和响应体资源。
            response.close();
        }
    }

    // HEAD 响应：只包含远程资源元信息，不包含响应体。
    public record HttpHeadResponse(String etag, String lastModified, String contentType, Long contentLength,
                                   String fileName) {
    }
}
