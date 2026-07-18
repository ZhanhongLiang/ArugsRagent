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
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 查询术语归一化规则的管理服务。
 *
 * <p>规则用于将用户口语、别名或型号写法转换成知识库更容易命中的标准术语；
 * 任一变更后清空缓存，避免问题重写继续使用旧词典。</p>
 */
@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    private final QueryTermMappingMapper queryTermMappingMapper;
    private final QueryTermMappingCacheManager queryTermMappingCacheManager;

    @Override
    public String create(QueryTermMappingCreateRequest requestParam) {
        // 词条在写入前去空白，防止视觉相同但包含前后空格的重复规则。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));

        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? (requestParam.getEnabled() ? 1 : 0) : 1);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));

        queryTermMappingMapper.insert(record);
        // 运行时映射通常从缓存读取，写库后必须主动失效。
        queryTermMappingCacheManager.clearCache();
        return String.valueOf(record.getId());
    }

    @Override
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        QueryTermMappingDO record = loadById(id);

        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }

        // 修改任何匹配条件、优先级或启用状态都会影响重写结果。
        queryTermMappingMapper.updateById(record);
        queryTermMappingCacheManager.clearCache();
    }

    @Override
    public void delete(String id) {
        QueryTermMappingDO record = loadById(id);
        // 删除同样要清缓存，否则已删除别名仍可能命中。
        queryTermMappingCacheManager.clearCache();
    }

    @Override
    public QueryTermMappingVO queryById(String id) {
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        // 数值越小优先级越高；相同优先级再按最新修改时间展示。
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    private QueryTermMappingDO loadById(String id) {
        // 所有单条操作复用统一的不存在校验。
        QueryTermMappingDO record = queryTermMappingMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
