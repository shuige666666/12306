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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.RegionDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.RegionMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_ALL_REGION_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;

/**
 * 查询列车车票流程过滤器之验证出发地和目的地数据是否正确
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainTicketQueryParamVerifyChainFilter implements TrainTicketQueryChainFilter<TicketPageQueryReqDTO> {

    private final RegionMapper regionMapper;
    private final StationMapper stationMapper;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;

    /**
     * 缓存数据为空并且已经加载过标识
     */
    private static boolean CACHE_DATA_ISNULL_AND_LOAD_FLAG = false;

    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        // 获取redis的hash操作接口
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        HashOperations<String, Object, Object> hashOperations = stringRedisTemplate.opsForHash();
        // 查询出发站点和到达站点是否存在，如果不存在也一样属于异常数据
        List<Object> actualExistList = hashOperations.multiGet(
                QUERY_ALL_REGION_LIST,
                // 同时查询出发站(requestParam.getFromStation())和目的站(requestParam.getToStation())
                // 第二个参数形如{HNH,JNK},而在hash中这两个hashkey对应的就分别是“海宁”和“济南”。若找不到就为null
                ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation())
        );
        // 返回结果就像是：actualExistList = ["北京", "上海"] 或者 ["北京", null] 或者 [null, null]
        // 统计查询结果中为空（即不存在）的数量
        // 这里有个坑的地方，就算为空，也会返回数据，所以我们通过 filter 判断对象是否为空
        long emptyCount = actualExistList.stream()
                .filter(Objects::isNull)    // 过滤出值为null的项（表示该站点不存在）
                .count();   // 统计不存在站点的数量
        // 【情况A：两个站点都存在】如果为空的记录是0的话，就证明出发站点和到达站点存在，正常返回即可
        if (emptyCount == 0L) {
            return;
        }
        // 【情况B：需要进一步处理】
        // 如果出发站点和到达站点都不存在或者仅存在一个，直接抛出异常
        // FLAG = true 代表已经加载过一次，此时还是空，证明说数据库也没有这两个站点信息，抛异常
        if (emptyCount == 1L || (emptyCount == 2L && CACHE_DATA_ISNULL_AND_LOAD_FLAG && distributedCache.hasKey(QUERY_ALL_REGION_LIST))) {
            throw new ClientException("出发地或目的地不存在");
        }
        /**
         * 缓存未命中时
         * 即 emptyCount == 2L 但是 CACHE_DATA_ISNULL_AND_LOAD_FLAG==false 且缓存不存在
         */
        // 如果FLAG=false代表有可能缓存没有数据，但数据库可能有，此时向下查询数据库
        // 为了避免缓存击穿，所以这里是用了分布式锁
        RLock lock = redissonClient.getLock(LOCK_QUERY_ALL_REGION_LIST);
        // 加锁（防止多个线程同时重建缓存）
        lock.lock();
        try {
            // 获取完分布式锁，避免重复且无用的加载数据库，通过双重判定锁的形式，再查询一次缓存
            // 【双重检查锁定模式：再次检查缓存】。
            if (distributedCache.hasKey(QUERY_ALL_REGION_LIST)) {
                actualExistList = hashOperations.multiGet(
                        QUERY_ALL_REGION_LIST,
                        ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation())
                );
                emptyCount = actualExistList.stream().filter(Objects::nonNull).count();
                if (emptyCount != 2L) {
                    throw new ClientException("出发地或目的地不存在");
                }
                return;
            }
            // 【缓存重建：从数据库加载数据】
            // 因为站点是可以传城市名称的，也就是 Region，所以我们需要查询站点以及城市两张表
            List<RegionDO> regionDOList = regionMapper.selectList(Wrappers.emptyWrapper());
            List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
            HashMap<Object, Object> regionValueMap = Maps.newHashMap();
            for (RegionDO each : regionDOList) {
                regionValueMap.put(each.getCode(), each.getName());
            }
            for (StationDO each : stationDOList) {
                regionValueMap.put(each.getCode(), each.getName());
            }
            // 查询完后，通过 putAll 的形式存入缓存，避免多次 put 浪费网络 IO
            hashOperations.putAll(QUERY_ALL_REGION_LIST, regionValueMap);
            // 设置 FLAG = true，代表已经加载过初始化数据
            CACHE_DATA_ISNULL_AND_LOAD_FLAG = true;
            // 再查询一次，查看是否存在。这里偷懒了，其实我们可以通过 regionValueMap 直接判断
            emptyCount = regionValueMap.keySet().stream()
                    .filter(each -> StrUtil.equalsAny(each.toString(), requestParam.getFromStation(), requestParam.getToStation()))
                    .count();
            // 如果加载后还是为空，那么直接抛出异常
            if (emptyCount != 2L) {
                throw new ClientException("出发地或目的地不存在");
            }
        } finally {
            // 分布式锁解锁
            lock.unlock();
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
