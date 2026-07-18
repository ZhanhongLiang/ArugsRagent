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

package com.nageoffer.ai.ragent.framework.idempotent;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * 幂等键中 SpEL 表达式的解析工具。
 *
 * <p>注解可以写固定字符串，也可以写 {@code #request.id} 这类依赖方法参数的表达式；
 * 本工具负责识别两者并将参数名绑定到 Spring EL 上下文。</p>
 */
public final class SpELUtil {

    /** 通过反射读取被拦截方法的编译参数名，用于给 SpEL 变量命名。 */
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    /** Spring 官方 SpEL 解析器实例，可复用且无状态。 */
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 判断传入内容是否包含 SpEL 标识，只有表达式才进入解析流程。
     *
     * @param spEl spEL 表达式
     * @return 实际使用的 spEL 表达式
     */
    public static Object parseKey(String spEl, Method method, Object[] contextObj) {
        // # 表示变量引用，T( 表示调用类型静态成员；两者均需要 EL 解析。
        List<String> spELFlag = ListUtil.of("#", "T(");
        Optional<String> optional = spELFlag.stream().filter(spEl::contains).findFirst();
        if (optional.isPresent()) {
            return parse(spEl, method, contextObj);
        }
        return spEl;
    }

    /**
     * 在方法参数上下文中计算 SpEL 表达式。
     *
     * @param spEl       spEl 表达式
     * @param contextObj 上下文对象
     * @return 解析的字符串值
     */
    public static Object parse(String spEl, Method method, Object[] contextObj) {
        // 将表达式文本编译为可执行对象。
        Expression exp = EXPRESSION_PARSER.parseExpression(spEl);
        // 参数名例如 request，会被绑定为 #request 供注解表达式访问。
        String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (ArrayUtil.isNotEmpty(params)) {
            // 按方法形参位置绑定实际入参；表达式求值时即可访问对应变量。
            for (int len = 0; len < params.length; len++) {
                context.setVariable(params[len], contextObj[len]);
            }
        }
        return exp.getValue(context);
    }
}
