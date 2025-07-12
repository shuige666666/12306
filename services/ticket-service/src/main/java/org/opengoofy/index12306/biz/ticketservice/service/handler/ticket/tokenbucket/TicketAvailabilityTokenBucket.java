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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.Assert;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

/**
 * 列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketAvailabilityTokenBucket {

    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;
    private final SeatService seatService;
    private final TrainMapper trainMapper;

    private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH = "lua/ticket_availability_token_bucket.lua";
    private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH = "lua/ticket_availability_rollback_token_bucket.lua";

    /**
     * 获取车站间令牌桶中的令牌访问
     * 如果返回 {@link Boolean#TRUE} 代表可以参与接下来的购票下单流程
     * 如果返回 {@link Boolean#FALSE} 代表当前访问出发站点和到达站点令牌已被拿完，无法参与购票下单等逻辑
     *
     * @param requestParam 购票请求参数入参
     * @return 是否获取列车车票余量令牌桶中的令牌返回结果
     */
    public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
        // 1. 获取列车信息
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(),
                TrainDO.class,
                () -> trainMapper.selectById(requestParam.getTrainId()),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        // 2. 获取列车经停站之间的数据集合，因为一旦失效要读取整个列车的令牌并重新赋值
        // 即获取这趟列车从始发站到终点站的所有连续区间（例如：A-B, B-C, C-D）。
        // 这个列表将用于初始化或重新加载令牌桶，因为令牌桶的粒度是针对每个区间和座位类型的。
        List<RouteDTO> routeDTOList = trainStationService
                .listTrainStationRoute(requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());

        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();

        // 令牌容器是个 Hash 结构
        // 3. 组装令牌桶在 Redis 中的 Hash Key
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        // 4.判断令牌容器是否存在
        Boolean hasKey = distributedCache.hasKey(tokenBucketHashKey);
        // 5.如果令牌容器 Hash 数据结构不存在了，执行加载流程
        if (!hasKey) {
            // 5.1.为了避免出现并发读写问题，所以这里通过分布式锁锁定
            RLock lock = redissonClient.getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
            if (!lock.tryLock()) {
                throw new ServiceException("购票异常，请稍候再试");
            }
            try {
                // 5.2.双重判定锁，避免加载数据请求多次请求数据库
                Boolean hasKeyTwo = distributedCache.hasKey(tokenBucketHashKey);
                if (!hasKeyTwo) {
                    // 根据列车类型获取所有支持的座位类型。
                    List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
                    // 用于构建 Redis Hash 结构的本地 Map。
                    Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
                    // 遍历列车的所有区间（例如：北京-天津，天津-济南）。
                    for (RouteDTO each : routeDTOList) {
                        // 查询每个区间每种座位类型，当前实际可用的座位数量。这是令牌桶的初始“库存”来源。
                        List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.parseLong(requestParam.getTrainId()), each.getStartStation(), each.getEndStation(), seatTypes);
                        for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
                            // 组装 Hash 数据结构内部的 Key  "起始站_终点站_座位类型" , 即如 “济南西_宁波_0”
                            String buildCacheKey = StrUtil.join("_", each.getStartStation(), each.getEndStation(), eachSeatTypeCountDTO.getSeatType());
                            // 一个 Hash 结构下有很多 Key，为了避免多次网络 IO，这里组装成一个本地 Map，通过 putAll 方法请求一次 Redis
                            ticketAvailabilityTokenMap.put(buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
                        }
                    }
                    // 将组装好的 Map 数据，赋值到 Redis
                    stringRedisTemplate.opsForHash().putAll(TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId(), ticketAvailabilityTokenMap);
                }
            } finally {
                lock.unlock();
            }
        }
        // 6.获取到 Redis 执行的 Lua 脚本的内容
        // Singleton 确保脚本只加载一次。
        DefaultRedisScript<String> actual = Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(String.class);
            return redisScript;
        });
        Assert.notNull(actual);
        // 7. 准备 Lua 脚本所需的参数
        // 7.1. 乘客购票的座位类型及数量统计，统计用户请求中每种座位类型需要购买的数量（例如：三等座2张，二等座1张）。
        Map<Integer, Long> seatTypeCountMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
        // 将统计结果转换为 JSON 数组格式，以便作为参数传递给 Lua 脚本。
        // 示例：[{"seatType":"1","count":"2"}, {"seatType":"3","count":"1"}]
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));

        // 7.2.获取需要扣减的站点
        List<RouteDTO> takeoutRouteDTOList = trainStationService
                .listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());

        // 8.调用 Redis 执行 Lua 脚本。
        // `actual` 是加载的 Lua 脚本对象。
        // `Lists.newArrayList(tokenBucketHashKey, luaScriptKey)` 是存储 Redis Hash 结构的 Key 值，对应lua中的KEYS[1]、KEYS[2]
        // seatTypeCountArray：需要扣减的座位类型以及对应数量。ARGV[1]
        // takeoutRouteDTOList：需要扣减的列车站点。ARGV[2]
        // Lua 脚本内部会根据这些参数，遍历 `takeoutRouteDTOList` 中的每个区间，
        // 然后检查 `tokenBucketHashKey` 对应的 Hash 中，每个区间-座位类型的令牌是否足够扣减 `seatTypeCountArray` 中对应的数量。
        // 如果所有涉及的区间和座位类型的令牌都足够，则原子性地进行扣减并返回成功；否则，不进行任何扣减并返回失败。
        String resultStr = stringRedisTemplate.execute(
                actual,
                Lists.newArrayList(tokenBucketHashKey, luaScriptKey),
                JSON.toJSONString(seatTypeCountArray),
                JSON.toJSONString(takeoutRouteDTOList));
        // 9. 解析 Lua 脚本的执行结果
        // Lua 脚本通常会返回一个 JSON 字符串，这里将其解析为 `TokenResultDTO` 对象。
        TokenResultDTO result = JSON.parseObject(resultStr, TokenResultDTO.class);
        // 10. 返回最终结果
        return result == null
                ? TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build()  // 如果 Lua 脚本返回空，可能是异常情况，构建一个带有 tokenIsNull 标志的DTO
                : result;
    }

    /**
     * 回滚列车余量令牌，一般为订单取消或长时间未支付触发
     *
     * @param requestParam 回滚列车余量令牌入参
     */
    public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual);
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        List<RouteDTO> takeoutRouteDTOList = trainStationService.listTakeoutTrainStationRoute(String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(actualHashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
        if (result == null || !Objects.equals(result, 0L)) {
            log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("回滚列车余票令牌失败");
        }
    }

    /**
     * 删除令牌，一般在令牌与数据库不一致情况下触发
     *
     * @param requestParam 删除令牌容器参数
     */
    public void delTokenInBucket(PurchaseTicketReqDTO requestParam) {
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        stringRedisTemplate.delete(tokenBucketHashKey);
    }

    public void putTokenInBucket() {

    }

    public void initializeTokens() {

    }
}
