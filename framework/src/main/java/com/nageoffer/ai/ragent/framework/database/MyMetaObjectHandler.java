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

package com.nageoffer.ai.ragent.framework.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 源数据自动填充类
 */
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // 新增记录时填充创建时间，严格填充不会覆盖实体中已显式设置的值。
        strictInsertFill(metaObject, "createTime", Date::new, Date.class);
        // 新增时更新时间与创建时间保持同一初始化语义。
        strictInsertFill(metaObject, "updateTime", Date::new, Date.class);
        // 默认逻辑未删除，配合查询条件实现软删除。
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新记录时无条件刷新更新时间，保留创建时间和删除标记。
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}
