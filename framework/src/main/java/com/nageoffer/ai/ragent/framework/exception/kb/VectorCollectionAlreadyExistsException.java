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

package com.nageoffer.ai.ragent.framework.exception.kb;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;

/**
 * 创建已存在向量集合时抛出的服务端业务异常。
 *
 * <p>用专用类型保留“重复创建”的语义，调用方可选择复用已有集合而非将其视为一般系统故障。</p>
 */
public class VectorCollectionAlreadyExistsException extends ServiceException {

    /** @param collectionName 被重复创建的向量集合名称。 */
    public VectorCollectionAlreadyExistsException(String collectionName) {
        super("向量集合已存在，禁止重复创建：" + collectionName);
    }
}
