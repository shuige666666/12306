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

package org.opengoofy.index12306.framework.starter.designpattern.chain;

import org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 抽象责任链上下文
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
// CommandLineRunner：SpringBoot 启动完成后执行的回调函数
public final class AbstractChainContext<T> implements CommandLineRunner {
    // 存储责任链组件实现和责任链业务标识的容器
    // 比如：Key：购票验证过滤器 Val：HanlderA、HanlderB、HanlderC、......
    private final Map<String, List<AbstractChainHandler>> abstractChainHandlerContainer = new HashMap<>();

    /**
     * 责任链组件执行
     *
     * @param mark         责任链组件标识
     * @param requestParam 请求参数
     */
    public void handler(String mark, T requestParam) {
        // 通过 mark 获取到本次需要执行的责任链组件
        List<AbstractChainHandler> abstractChainHandlers = abstractChainHandlerContainer.get(mark);
        // 获取为空抛出异常
        if (CollectionUtils.isEmpty(abstractChainHandlers)) {
            throw new RuntimeException(String.format("[%s] Chain of Responsibility ID is undefined.", mark));
        }
        // 获取到的责任链组件依次执行
        abstractChainHandlers.forEach(each -> each.handler(requestParam));
    }

    @Override
    public void run(String... args) throws Exception {
        // 调用 SpirngIOC 工厂获取 AbstractChainHandler 接口类型的 Bean
        Map<String, AbstractChainHandler> chainFilterMap = ApplicationContextHolder
                .getBeansOfType(AbstractChainHandler.class);
        chainFilterMap.forEach((beanName, bean) -> {
            // 获取 mark（责任链业务标识）的处理器集合
            List<AbstractChainHandler> abstractChainHandlers = abstractChainHandlerContainer.get(bean.mark());
            // 如果不存在则创建一个集合
            if (CollectionUtils.isEmpty(abstractChainHandlers)) {
                abstractChainHandlers = new ArrayList();
            }
            // 添加到处理器集合中
            abstractChainHandlers.add(bean);
            // 对处理器集合执行顺序进行排序
            List<AbstractChainHandler> actualAbstractChainHandlers = abstractChainHandlers.stream()
                    .sorted(Comparator.comparing(Ordered::getOrder))
                    .collect(Collectors.toList());
            // 存入容器等待被运行时调用
            abstractChainHandlerContainer.put(bean.mark(), actualAbstractChainHandlers);
        });
    }
}
