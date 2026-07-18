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

package com.nageoffer.ai.ragent.framework.context;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Spring 应用上下文持有者
 * 用于在非 Spring 管理的类中获取 Spring 容器中的 Bean 实例
 * 通过实现 ApplicationContextAware 接口，在 Spring 容器启动时自动注入 ApplicationContext
 * 提供了一系列静态方法用于获取 Bean 实例和查询注解信息
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

    /** Spring 启动完成后保存的全局容器引用，供非 Bean 场景按需获取依赖。 */
    private static ApplicationContext CONTEXT;

    /**
     * Spring 回调入口：容器创建本 Bean 时注入 ApplicationContext。
     *
     * @param applicationContext 当前应用上下文
     * @throws BeansException 容器初始化异常时由 Spring 向上抛出
     */
    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        // 保存静态引用；后续静态工具方法都通过该引用委托给 Spring。
        ApplicationContextHolder.CONTEXT = applicationContext;
    }

    /**
     * 根据类型获取 Bean
     */
    public static <T> T getBean(Class<T> clazz) {
        // 按类型获取唯一 Bean；存在多个同类型 Bean 时由 Spring 抛出歧义异常。
        return CONTEXT.getBean(clazz);
    }

    /**
     * 根据名称获取 Bean
     */
    public static Object getBean(String name) {
        // 按 Bean 名称获取，适用于运行时动态选择实现的场景。
        return CONTEXT.getBean(name);
    }

    /**
     * 根据名称和类型获取 Bean
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        // 同时校验名称与类型，避免错误强转在更远处才暴露。
        return CONTEXT.getBean(name, clazz);
    }

    /**
     * 根据类型获取同类型的所有 Bean
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        // 返回所有同类型实现，常用于策略、工具执行器和供应商客户端自动发现。
        return CONTEXT.getBeansOfType(clazz);
    }

    /**
     * 查找 Bean 上的注解
     */
    public static <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType) {
        // 让调用方查询代理目标上的注解，无需自行处理 AOP 代理。
        return CONTEXT.findAnnotationOnBean(beanName, annotationType);
    }

    /**
     * 获取 ApplicationContext
     */
    public static ApplicationContext getInstance() {
        // 暴露容器本身仅用于框架级扩展；普通业务优先构造器注入。
        return CONTEXT;
    }
}
