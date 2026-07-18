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

package com.nageoffer.ai.ragent.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要由 Trace AOP 记录的普通 RAG 方法节点。
 *
 * <p>切面会在方法调用前后创建和结束节点；对于真正跨线程、跨 SSE 生命周期的节点，
 * 应改用 {@link RagStreamTraceSupport}，不能只依赖此注解。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /** 节点展示名称；为空时切面通常回退到方法名。 */
    String name() default "";

    /** 节点分类，例如 RETRIEVE、RERANK，用于 Trace 页面分组和指标统计。 */
    String type() default "METHOD";
}
