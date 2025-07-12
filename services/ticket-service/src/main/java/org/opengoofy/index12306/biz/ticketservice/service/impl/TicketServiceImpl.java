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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SourceEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatClassDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.framework.starter.log.annotation.ILog;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS_V2;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TOKEN_BUCKET_ISNULL;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil.convertDateToLocalTime;

/**
 * 车票接口实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService, CommandLineRunner {

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final PayRemoteService payRemoteService;
    private final StationMapper stationMapper;
    private final SeatService seatService;
    private final TrainStationService trainStationService;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryAbstractChainContext;
    private final AbstractChainContext<PurchaseTicketReqDTO> purchaseTicketAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private TicketService ticketService;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;
    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;

    // v1 版本存在严重的性能深渊问题，v2 版本完美的解决了该问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);

        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 根据传入参数中的城市代码获取对应的城市名称，stationDetails的格式为：["北京", "上海"] 或者 ["北京", null] 或者 [null, null]
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        // 记录stationDetails中城市为null的数量
        long count = stationDetails.stream().filter(Objects::isNull).count();
        if (count > 0) {
            // 如果有为null的城市就加锁更新缓存（因为前面已经判断过这个城市是存在的，所以说应该就是缓存太旧了）
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION_MAPPING);
            lock.lock();
            try {
                // 双重检查锁定：再次检查缓存，防止在等待锁期间其他线程已填充缓存
                stationDetails = stringRedisTemplate.opsForHash()
                        .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                count = stationDetails.stream().filter(Objects::isNull).count();
                if (count > 0) {
                    // 从数据库加载全量车站数据
                    List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
                    // 构建缓存数据并存入Redis
                    Map<String, String> regionTrainStationMap = new HashMap<>();
                    stationDOList.forEach(each -> regionTrainStationMap.put(each.getCode(), each.getRegionName()));
                    stringRedisTemplate.opsForHash().putAll(REGION_TRAIN_STATION_MAPPING, regionTrainStationMap);
                    stationDetails = new ArrayList<>();
                    stationDetails.add(regionTrainStationMap.get(requestParam.getFromStation()));
                    stationDetails.add(regionTrainStationMap.get(requestParam.getToStation()));
                }
            } finally {
                lock.unlock();
            }
        }
        /**
         * 【查询列车信息】
         * 以及对列车信息进行缓存处理
         */
        List<TicketListDTO> seatResults = new ArrayList<>();
        // 构建 Redis Hash 键，例如 "index12306-ticket-service:region_train_station:北京_宁波"
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        // 尝试从 Redis 缓存获取该路线的所有列车信息
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        if (MapUtil.isEmpty(regionTrainStationAllMap)) {
            // 在缓存中找不到这个车次信息
            // 老规矩上分布式锁
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION);
            lock.lock();
            try {
                // 老规矩双重判定锁
                regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
                // 加载数据库列车相关信息，并构建出一趟列车的详细记录
                if (MapUtil.isEmpty(regionTrainStationAllMap)) {
                    // 根据出发区域和到达区域查询列车与车站的关系
                    // queryWrapper是MyBatis-Plus 提供的查询条件封装对象，等于说把sql的where等语句封装到这里面，然后在后面selectList中就可以用
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getStartRegion, stationDetails.get(0))
                            .eq(TrainStationRelationDO::getEndRegion, stationDetails.get(1));
                    List<TrainStationRelationDO> trainStationRelationList = trainStationRelationMapper.selectList(queryWrapper);
                    // 这个trainStationRelationList里面带有对应这两个站点的车次id，再根据车次id获取这一趟列车的所有详细信息
                    for (TrainStationRelationDO each : trainStationRelationList) {
                        // 使用 distributedCache.safeGet 获取缓存中单个 TrainDO
                        // safeGet 就是安全获取缓存方案，底层加了分布式锁和双重判定锁
                        TrainDO trainDO = distributedCache.safeGet(
                                TRAIN_INFO + each.getTrainId(), // 缓存键
                                TrainDO.class,
                                () -> trainMapper.selectById(each.getTrainId()), // 缓存未命中时从 DB 加载
                                ADVANCE_TICKET_DAY, // 缓存过期时间
                                TimeUnit.DAYS);
                        // 根据列车信息、前面获得的列车站点关系信息封装 TicketListDTO
                        TicketListDTO result = new TicketListDTO();
                        result.setTrainId(String.valueOf(trainDO.getId()));
                        result.setTrainNumber(trainDO.getTrainNumber());
                        result.setDepartureTime(convertDateToLocalTime(each.getDepartureTime(), "HH:mm"));
                        result.setArrivalTime(convertDateToLocalTime(each.getArrivalTime(), "HH:mm"));
                        result.setDuration(DateUtil.calculateHourDifference(each.getDepartureTime(), each.getArrivalTime()));
                        result.setDeparture(each.getDeparture());
                        result.setArrival(each.getArrival());
                        result.setDepartureFlag(each.getDepartureFlag());
                        result.setArrivalFlag(each.getArrivalFlag());
                        result.setTrainType(trainDO.getTrainType());
                        result.setTrainBrand(trainDO.getTrainBrand());
                        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
                            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
                        }
                        long betweenDay = cn.hutool.core.date.DateUtil.betweenDay(each.getDepartureTime(), each.getArrivalTime(), false);
                        result.setDaysArrived((int) betweenDay);
                        // 根据售票时间判断是否已开售
                        result.setSaleStatus(new Date().after(trainDO.getSaleTime()) ? 0 : 1);
                        result.setSaleTime(convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"));
                        seatResults.add(result);
                        // 将单个列车信息序列化为 JSON 字符串并放入临时 Map，准备写入 Redis Hash
                        regionTrainStationAllMap.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()), JSON.toJSONString(result));
                    }
                    stringRedisTemplate.opsForHash().putAll(buildRegionTrainStationHashKey, regionTrainStationAllMap);
                }
            } finally {
                lock.unlock();
            }
        }
        // 查询出来列车基本信息后，开始对列车按照出发时间进行排序
        seatResults = CollUtil.isEmpty(seatResults)
                ? regionTrainStationAllMap.values().stream().map(each -> JSON.parseObject(each.toString(), TicketListDTO.class)).toList()
                : seatResults;
        seatResults = seatResults.stream().sorted(new TimeStringComparator()).toList();

        /**
         * 查询列车余票信息
         */
        for (TicketListDTO each : seatResults) {
            // 加载列车对应的座位价格数据
            // 如果查询 TRAIN_STATION_PRICE 数据为空，则加载数据库，并放入缓存
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> trainStationPriceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getDeparture, each.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, each.getArrival())
                                .eq(TrainStationPriceDO::getTrainId, each.getTrainId());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(trainStationPriceQueryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            // 反序列化价格列表
            List<TrainStationPriceDO> trainStationPriceDOList = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            trainStationPriceDOList.forEach(item -> {
                String seatType = String.valueOf(item.getSeatType());
                String keySuffix = StrUtil.join("_", each.getTrainId(), item.getDeparture(), item.getArrival());
                // 从 Redis Hash 中获取余票数量
                Object quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, seatType);
                int quantity = Optional.ofNullable(quantityObj)
                        .map(Object::toString)
                        .map(Integer::parseInt)
                        .orElseGet(() -> {
                            // 如果 Redis 中没有余票，尝试从 seatMarginCacheLoader 加载
                            Map<String, String> seatMarginMap = seatMarginCacheLoader.load(String.valueOf(each.getTrainId()), seatType, item.getDeparture(), item.getArrival());
                            return Optional.ofNullable(seatMarginMap.get(String.valueOf(item.getSeatType()))).map(Integer::parseInt).orElse(0);
                        });
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            });
            // 将座位信息设置到列车对象中
            each.setSeatClassList(seatClassList);
        }
        /**
         * 构建这一条车票信息的返回数据
         */
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    // v2 版本更符合企业级高并发真实场景解决方案，完美解决了 v1 版本性能深渊问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
    // 其实还能有 v3 版本，性能估计在原基础上还能进一步提升一倍。不过 v3 版本太过于复杂，不易读且不易扩展，就不写具体的代码了。面试中 v2 版本已经够和面试官吹的了
    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();

        // 1. 获取车站区域映射信息（假设已缓存）
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        // 2. 直接从缓存获取列车列表并进行初步处理（假设已缓存且是最新的）
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        // 示例：seatResults = [{trainId: "G101", departure: "北京", arrival: "上海"},
        //                     {trainId: "D735", departure: "广州", arrival: "深圳"}]
        // 3. 批量获取所有列车的票价信息（使用 Redis Pipeline）
        // 3.1. 构造要查询的redis key
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        // 3.2. 对每一个key（使用Pipelined）进行循环查询
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each ->
                    connection.stringCommands().get(each.getBytes())
            );
            return null;
        });
        // 4. 解析票价信息，并准备批量获取余票信息的键
        // 存储所有列车所有席别的价格DO
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        // 存储所有列车所有席别的余票Redis Hash键
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        // 遍历每个列车的票价JSON字符串
        for (Object each : trainStationPriceObjs) {
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            // 将当前列车的所有席别价格DO添加到总列表中
            trainStationPriceDOList.addAll(trainStationPriceList);
            // 遍历当前列车的每个席别价格DO,构建redis hash键和hash字段
            for (TrainStationPriceDO item : trainStationPriceList) {
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        // 5. 批量获取所有列车所有席别的余票信息（使用 Redis Pipeline）
        List<Object> trainStationRemainingObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            // trainStationRemainingKeyList 和 trainStationPriceDOList 的顺序必须一一对应
            for (int i = 0; i < trainStationRemainingKeyList.size(); i++) {
                // HGET key field 命令，key 是 trainStationRemainingKeyList.get(i)，field 是 seatType
                connection.hashCommands().hGet(trainStationRemainingKeyList.get(i).getBytes(), trainStationPriceDOList.get(i).getSeatType().toString().getBytes());
            }
            return null;
        });
        // 6. 将获取到的价格和余票信息组装到每个列车对象中
        // 依赖于 trainStationPriceDOList 和 trainStationRemainingObjs 的顺序与 seatResults 中列车的顺序一致
        for (TicketListDTO each : seatResults) {
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            // 获取当前列车对应数量的价格和余票子列表，并从原列表中移除，以便下一趟列车使用
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString())) // 余票数量
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP)) // 价格
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        // 7. 构建最终响应
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    /**
     * 购票V1
     * @param requestParam 车票购买请求参数
     * @return
     */
    @ILog
    @Idempotent(
            //@Idempotents是幂等组件，在这里用于实现购票幂等性
            uniqueKeyPrefix = "index12306-ticket:lock_purchase-tickets:",
            // 有关下面的SpEL表达式，简单来说，第一部分通过 Spring IOC 容器获取 unique-name 这个参数，如果为空返回空字符串，第三部分负责获取当前登录用户，第二部分负责将两个值通过 _ 字符进行拼接。
            // 举例：你设置了 unique-name 参数为 mading，当前用户名 admin，那么最终就是 mading_admin。
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()",
            message = "正在执行下单流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV1(PurchaseTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // v1 版本购票存在 4 个较为严重的问题，v2 版本相比较 v1 版本更具有业务特点以及性能，整体提升较大
        // 可以看到，这里使用的是车次级锁（trainId）
        String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS, requestParam.getTrainId()));
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            return ticketService.executePurchaseTickets(requestParam);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 用于存储本地ReentrantLock实例的缓存。
     * 键是锁的唯一标识，值是对应的ReentrantLock对象。
     * 使用Caffeine缓存是为了管理锁的生命周期，防止无限增长的锁对象，并自动清理不常用的锁。
     * expireAfterWrite(1, TimeUnit.DAYS) 表示如果一个锁对象在1天内没有被写入（即没有被访问或创建），则会自动从缓存中移除。
     */
    private final Cache<String, ReentrantLock> localLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    /**
     * 用于存储令牌刷新状态的缓存。键是 trainId，值是一个Object（仅作占位符使用）。
     * expireAfterWrite(10, TimeUnit.MINUTES) 表示如果一个列车的令牌刷新状态在10分钟内没有被写入，则会自动从缓存中移除。
     * 它的主要作用是作为一个简单的本地“信号量”或“标志位”，防止在令牌桶为空时，多个线程同时去尝试刷新令牌桶。
     * 当某个列车`trainId`的令牌耗尽并需要刷新时，会尝试将该`trainId`放入此缓存。
     * 放入成功后，其他线程在10分钟内再次遇到该`trainId`的令牌为空情况时，会发现缓存中已有记录，从而避免重复的刷新操作。
     */
    private final Cache<String, Object> tokenTicketsRefreshMap = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * 购票V2（这个函数中主要是进行了令牌的添加和公平锁以及锁的粒度等的改造）
     * @param requestParam 车票购买请求参数
     * @return
     */
    @ILog
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:lock_purchase-tickets:",
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()",
            message = "正在执行下单流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        // 1. 利用责任链模式验证参数，即验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // 2. 在获取锁之前先看看能否获取到令牌，利用令牌先进行限流
        TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);
        // 3. 如果获取不到令牌，说明当前列车车次座位已用尽，尝试异步刷新余票信息
        if (tokenResult.getTokenIsNull()) {
            // 检查 tokenTicketsRefreshMap 中是否已经有其他线程正在尝试刷新此列车的令牌桶。
            Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
            // 如果当前JVM内没有线程在刷新此列车的令牌桶，则尝试进入同步块。
            if (ifPresentObj == null) {
                // 利用 Caffeine 本地缓存防止并发重复触发刷新逻辑（10分钟内最多刷新一次）
                // 这是一个类级别的锁，意味着在整个JVM内，只有一个线程能同时进入这个同步块。保证`tokenTicketsRefreshMap`的写入操作以及`tokenIsNullRefreshToken`的调用是原子性的。
                synchronized (TicketService.class) {
                    // 双重检查
                    if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {
                        ifPresentObj = new Object();
                        tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);// 将该列车ID放入缓存，标记为正在刷新
                        // 3.2 异步触发令牌刷新流程（延迟10秒执行）
                        tokenIsNullRefreshToken(requestParam, tokenResult);
                    }
                }
            }
            throw new ServiceException("列车站点已无余票");
        }

        // 4.分层构造锁，按车次+座位类型分组加锁

        // 存储本次请求需要获取的本地锁的集合(ReentrantLock是一种可重入的互斥锁)
        List<ReentrantLock> localLockList = new ArrayList<>();
        // 存储本次请求需要获取的分布式锁的集合(RLock是Redisson提供的分布式可重入锁)
        List<RLock> distributedLockList = new ArrayList<>();
        // 按照座位类型进行分组
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        seatTypeMap.forEach((searType, count) -> {
            String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getTrainId(), searType));
            ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
            if (localLock == null) {
                synchronized (TicketService.class) {
                    if ((localLock = localLockMap.getIfPresent(lockKey)) == null) {
                        localLock = new ReentrantLock(true);
                        localLockMap.put(lockKey, localLock);
                    }
                }
            }
            // 添加到本地锁集合
            localLockList.add(localLock);
            RLock distributedLock = redissonClient.getFairLock(lockKey);
            // 添加到分布式锁集合
            distributedLockList.add(distributedLock);
        });
        // 5. 加锁（本地 + 分布式），确保并发下单时线程安全
        try {
            // 循环请求本地锁
            localLockList.forEach(ReentrantLock::lock);
            // 循环请求分布式锁
            distributedLockList.forEach(RLock::lock);
            // 6. 执行实际的购票操作
            return ticketService.executePurchaseTickets(requestParam);
        } finally {
            // 7. 释放锁
            // 释放本地锁
            localLockList.forEach(localLock -> {
                try {
                    localLock.unlock();
                } catch (Throwable ignored) {
                }
            });
            // 释放分布式锁
            distributedLockList.forEach(distributedLock -> {
                try {
                    distributedLock.unlock();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /**
     * V1和V2的购票详细操作
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)  //rollbackFor = Throwable.class: 表示当方法内部抛出任何异常（包括Error和Exception）时，事务都将回滚。
    public TicketPurchaseRespDTO executePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        // 1. 初始化数据
        // 用于存储购票成功的票据详情，最终返回给用户。
        List<TicketOrderDetailRespDTO> ticketOrderDetailResults = new ArrayList<>();
        String trainId = requestParam.getTrainId();
        // 2. 获取车次信息（缓存优先）
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        // 3. 执行座位选择算法
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = trainSeatTypeSelector.select(trainDO.getTrainType(), requestParam);
        // 4. 构建车票实体并批量保存
        List<TicketDO> ticketDOList = trainPurchaseTicketResults.stream()
                .map(each -> TicketDO.builder()
                        .username(UserContext.getUsername())
                        .trainId(Long.parseLong(requestParam.getTrainId()))
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .passengerId(each.getPassengerId())
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                        .build())
                .toList();
        // 批量保存到DB,用的是Mybaties-Plus中的批量插入方法saveBatch()
        saveBatch(ticketDOList);
        // 5. 创建订单，即构建远程订单创建请求
        Result<String> ticketOrderResult;   //用于存放创建订单的结果
        try {
            // 5.1 构建订单项
            List<TicketOrderItemCreateRemoteReqDTO> orderItemCreateRemoteReqDTOList = new ArrayList<>();
            trainPurchaseTicketResults.forEach(each -> {
                // 订单项DTO（远程调用用）
                TicketOrderItemCreateRemoteReqDTO orderItemCreateRemoteReqDTO = TicketOrderItemCreateRemoteReqDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .phone(each.getPhone())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                // 订单详情DTO（返回结果用）
                TicketOrderDetailRespDTO ticketOrderDetailRespDTO = TicketOrderDetailRespDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                orderItemCreateRemoteReqDTOList.add(orderItemCreateRemoteReqDTO);
                ticketOrderDetailResults.add(ticketOrderDetailRespDTO);
            });
            LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                    .eq(TrainStationRelationDO::getTrainId, trainId)
                    .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationRelationDO::getArrival, requestParam.getArrival());
            // 5.2 获取车站时间信息
            TrainStationRelationDO trainStationRelationDO = trainStationRelationMapper.selectOne(queryWrapper);
            // 5.3 构建主订单请求
            TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderTime(new Date())
                    .source(SourceEnum.INTERNET.getCode())
                    .trainNumber(trainDO.getTrainNumber())
                    .departureTime(trainStationRelationDO.getDepartureTime())
                    .arrivalTime(trainStationRelationDO.getArrivalTime())
                    .ridingDate(trainStationRelationDO.getDepartureTime())
                    .userId(UserContext.getUserId())
                    .username(UserContext.getUsername())
                    .trainId(Long.parseLong(requestParam.getTrainId()))
                    .ticketOrderItems(orderItemCreateRemoteReqDTOList)
                    .build();
            // 5.4 调用订单服务
            ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);
            if (!ticketOrderResult.isSuccess() || StrUtil.isBlank(ticketOrderResult.getData())) {
                log.error("订单服务调用失败，返回结果：{}", ticketOrderResult.getMessage());
                throw new ServiceException("订单服务调用失败");
            }
        } catch (Throwable ex) {
            log.error("远程调用订单服务创建错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return new TicketPurchaseRespDTO(ticketOrderResult.getData(), ticketOrderDetailResults);
    }

    @Override
    public PayInfoRespDTO getPayInfo(String orderSn) {
        return payRemoteService.getPayInfo(orderSn).getData();
    }

    @ILog
    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        Result<Void> cancelOrderResult = ticketOrderRemoteService.cancelTicketOrder(requestParam);
        if (cancelOrderResult.isSuccess() && !StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> ticketOrderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
            org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
            String trainId = String.valueOf(ticketOrderDetail.getTrainId());
            String departure = ticketOrderDetail.getDeparture();
            String arrival = ticketOrderDetail.getArrival();
            List<TicketOrderPassengerDetailRespDTO> trainPurchaseTicketResults = ticketOrderDetail.getPassengerDetails();
            try {
                seatService.unlock(trainId, departure, arrival, BeanUtil.convert(trainPurchaseTicketResults, TrainPurchaseTicketRespDTO.class));
            } catch (Throwable ex) {
                log.error("[取消订单] 订单号：{} 回滚列车DB座位状态失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
            ticketAvailabilityTokenBucket.rollbackInBucket(ticketOrderDetail);
            try {
                StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                Map<Integer, List<TicketOrderPassengerDetailRespDTO>> seatTypeMap = trainPurchaseTicketResults.stream()
                        .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType));
                List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
                routeDTOList.forEach(each -> {
                    String keySuffix = StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation());
                    seatTypeMap.forEach((seatType, ticketOrderPassengerDetailRespDTOList) -> {
                        stringRedisTemplate.opsForHash()
                                .increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), ticketOrderPassengerDetailRespDTOList.size());
                    });
                });
            } catch (Throwable ex) {
                log.error("[取消关闭订单] 订单号：{} 回滚列车Cache余票失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
        }
    }

    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填
        refundReqDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), requestParam);
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> orderDetailRespDTOResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (!orderDetailRespDTOResult.isSuccess() && Objects.isNull(orderDetailRespDTOResult.getData())) {
            throw new ServiceException("车票订单不存在");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetailRespDTO = orderDetailRespDTOResult.getData();
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = ticketOrderDetailRespDTO.getPassengerDetails();
        if (CollectionUtil.isEmpty(passengerDetails)) {
            throw new ServiceException("车票子订单不存在");
        }
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        if (RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())) {
            TicketOrderItemQueryReqDTO ticketOrderItemQueryReqDTO = new TicketOrderItemQueryReqDTO();
            ticketOrderItemQueryReqDTO.setOrderSn(requestParam.getOrderSn());
            ticketOrderItemQueryReqDTO.setOrderItemRecordIds(requestParam.getSubOrderRecordIdReqList());
            Result<List<TicketOrderPassengerDetailRespDTO>> queryTicketItemOrderById = ticketOrderRemoteService.queryTicketItemOrderById(ticketOrderItemQueryReqDTO);
            List<TicketOrderPassengerDetailRespDTO> partialRefundPassengerDetails = passengerDetails.stream()
                    .filter(item -> queryTicketItemOrderById.getData().contains(item))
                    .collect(Collectors.toList());
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.PARTIAL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(partialRefundPassengerDetails);
        } else if (RefundTypeEnum.FULL_REFUND.getType().equals(requestParam.getType())) {
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.FULL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(passengerDetails);
        }
        if (CollectionUtil.isNotEmpty(passengerDetails)) {
            Integer partialRefundAmount = passengerDetails.stream()
                    .mapToInt(TicketOrderPassengerDetailRespDTO::getAmount)
                    .sum();
            refundReqDTO.setRefundAmount(partialRefundAmount);
        }
        refundReqDTO.setOrderSn(requestParam.getOrderSn());
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess() && Objects.isNull(refundRespDTOResult.getData())) {
            throw new ServiceException("车票订单退款失败");
        }
        return null; // 暂时返回空实体
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).distinct().collect(Collectors.toList());
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).distinct().collect(Collectors.toList());
    }

    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> resultSeatClassList = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            for (SeatClassDTO item : each.getSeatClassList()) {
                resultSeatClassList.add(item.getType());
            }
        }
        return resultSeatClassList.stream().toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> trainBrandSet = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            if (StrUtil.isNotBlank(each.getTrainBrand())) {
                trainBrandSet.addAll(StrUtil.split(each.getTrainBrand(), ",").stream().map(Integer::parseInt).toList());
            }
        }
        return trainBrandSet.stream().toList();
    }

    private final ScheduledExecutorService tokenIsNullRefreshExecutor = Executors.newScheduledThreadPool(1);

    private void tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResult) {
        // 1. 获取分布式锁，避免多个节点重复刷新（基于车次ID）
        RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL, requestParam.getTrainId()));
        if (!lock.tryLock()) {
            return;// 无法获取锁直接返回，说明已有线程在刷新
        }
        // 2. 使用调度线程池延迟10秒执行刷新任务（避免频繁触发）
        tokenIsNullRefreshExecutor.schedule(() -> {
            try {
                // 3. 解析无令牌的座位类型及缺口数（seatType_count）
                List<Integer> seatTypes = new ArrayList<>();
                Map<Integer, Integer> tokenCountMap = new HashMap<>();
                tokenResult.getTokenIsNullSeatTypeCounts().stream()
                        .map(each -> each.split("_"))// 分割 seatType_count
                        .forEach(split -> {
                            int seatType = Integer.parseInt(split[0]);
                            seatTypes.add(seatType);
                            tokenCountMap.put(seatType, Integer.parseInt(split[1]));
                        });
                // 4. 查询数据库中当前列车站点的实际余票数
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);
                // 5. 比对实际余票是否满足缺口，如果满足，则清空令牌桶中的缓存（准备重新加载）
                for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                    Integer tokenCount = tokenCountMap.get(each.getSeatType());
                    if (tokenCount <= each.getSeatCount()) {
                        ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                        break;
                    }
                }
            } finally {
                // 6. 解锁
                lock.unlock();
            }
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    public void run(String... args) throws Exception {
        ticketService = ApplicationContextHolder.getBean(TicketService.class);
    }
}
