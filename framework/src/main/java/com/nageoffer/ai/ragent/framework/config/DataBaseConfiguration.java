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

package com.nageoffer.ai.ragent.framework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * MyBatis-Plus 持久层基础配置。
 *
 * <p>数据库采用 PostgreSQL，因此分页 SQL 必须通过 PostgreSQL 方言插件生成；
 * 同时注册统一的审计字段填充器。</p>
 */
@Configuration
public class DataBaseConfiguration {

    /** @return 配置 PostgreSQL 方言的分页拦截器，避免按默认方言拼接分页 SQL。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 外层拦截器可容纳多个 InnerInterceptor，当前先注册分页能力。
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /** @return 在插入和更新时写入创建人、更新人、时间等审计字段的处理器。 */
    @Bean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}
