-- KEYS[1]: 令牌桶在 Redis 中的 Hash Key (e.g., "ticket_availability_token_bucket:trainId")
-- KEYS[2]: 用户购买的出发站点和到达站点字符串 (e.g., "北京南_南京南")

-- ARGV[1]: JSON 字符串，表示用户购买的座位类型及数量统计 (e.g., '[{"seatType":"1","count":"2"}, {"seatType":"3","count":"1"}]')
-- ARGV[2]: JSON 字符串，表示需要扣减的列车站点区间列表 (e.g., '[{"startStation":"北京南","endStation":"天津"}, {"startStation":"天津","endStation":"南京南"}]')
local inputString = KEYS[2]
-- 因为 Redis Key 序列化器的问题，会把 mading: 给加上
-- 所以这里需要把 mading: 给删除，仅保留北京南_南京南
-- actualKey 就是北京南_南京南
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

-- 解码 ARGV[1]，获取用户购买的座位类型和数量列表
local jsonArrayStr = ARGV[1]
local jsonArray = cjson.decode(jsonArrayStr)

-- 初始化结果变量
local result = {}
local tokenIsNull = false   -- 标记是否有座位令牌不足
local tokenIsNullSeatTypeCounts = {}    -- 记录哪些座位类型和数量不足

-- 第一阶段：预检查（不进行实际扣减），判断令牌是否足够
for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    local actualInnerHashKey = actualKey .. "_" .. seatType
    --  判断指定座位 Token 余量是否超过购买人数
    local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
    --  如果超过那么设置 TOKEN 获取失败，以及记录失败座位类型和获取数量
    if ticketSeatAvailabilityTokenValue < count then
        tokenIsNull = true
        table.insert(tokenIsNullSeatTypeCounts, seatType .. "_" .. count)
    end
end

-- 如果预检查发现有令牌不足，则立即返回结果，不进行实际扣减
result['tokenIsNull'] = tokenIsNull
if tokenIsNull then
    result['tokenIsNullSeatTypeCounts'] = tokenIsNullSeatTypeCounts
    -- 返回 JSON 字符串，包含不足信息
    return cjson.encode(result)
end

-- 如果预检查通过（所有令牌都充足），则进入第二阶段：实际的令牌扣减

-- 获取需要扣减的列车站点区间列表（JSON 字符串）
local alongJsonArrayStr = ARGV[2]
--  序列化成对象
local alongJsonArray = cjson.decode(alongJsonArrayStr)

-- 外层循环：遍历用户购买的每种座位类型及数量
for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    -- 内层循环：遍历用户行程所覆盖的每个连续区间
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        -- 构建内部 Hash Key，格式为 "区间始发站_区间终点站_座位类型"
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        -- 原子性地扣减该区间和座位类型的令牌数量
        redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), -count)
    end
end

return cjson.encode(result)
