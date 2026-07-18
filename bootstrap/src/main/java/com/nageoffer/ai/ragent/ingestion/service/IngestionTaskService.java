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

package com.nageoffer.ai.ragent.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 可配置摄取管道的任务服务端口。
 *
 * <p>普通来源和 multipart 上传最终复用同一执行内核；任务、节点日志与结果查询对控制器统一暴露。</p>
 */
public interface IngestionTaskService {

    /**
     * 根据 JSON 文档来源执行摄取。
     *
     * @param request 创建请求
     * @return 摄入结果
     */
    IngestionResult execute(IngestionTaskCreateRequest request);

    /**
     * 读取 multipart 文件字节并执行摄取。
     *
     * @param pipelineId 流水线 ID，不能为空，决定节点顺序与参数
     * @param file 用户上传的原始文件
     * @return 摄入结果
     */
    IngestionResult upload(String pipelineId, MultipartFile file);

    /**
     * 获取任务主表摘要及序列化后的节点日志概览。
     *
     * @param taskId 任务ID
     * @return 任务VO
     */
    IngestionTaskVO get(String taskId);

    /**
     * 按状态可选过滤地分页查询任务历史。
     *
     * @param page   分页参数
     * @param status 状态筛选
     * @return 分页结果
     */
    IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status);

    /**
     * 获取单个任务按执行顺序排列的逐节点记录。
     *
     * @param taskId 任务ID
     * @return 节点列表
     */
    List<IngestionTaskNodeVO> listNodes(String taskId);
}
