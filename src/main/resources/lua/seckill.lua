-- 参数说明：
-- KEYS[1]: 商品库存 Key (例如: seckill:stock:1001)
-- KEYS[2]: 已购买用户集合 Key (例如: seckill:users:1001)
-- KEYS[3]: 待处理订单队列 Key (例如: seckill:pending:1001)，用于库存对账
-- ARGV[1]: 用户 ID
-- ARGV[2]: 过期时间（秒）

local stockKey = KEYS[1]
local userListKey = KEYS[2]
local pendingKey = KEYS[3]
local userId = ARGV[1]
local expireTime = ARGV[2]

-- 1. 校验用户是否重复购买 (利用 Set 集合)
if redis.call('sismember', userListKey, userId) == 1 then
    return -1 -- 重复购买
end

-- 2. 校验库存
local stock = tonumber(redis.call('get', stockKey))
-- 如果库存 Key 不存在或库存 <= 0
if stock == nil or stock <= 0 then
    return 0 -- 库存不足
end

-- 3. 扣减库存 & 记录用户 & 写入待处理队列（原子操作）
--    待处理队列用于防止：Lua扣减库存成功 → 但后续MQ/MySQL流程中断 → 库存丢失
--    对账补偿任务会扫描此队列，确保每笔扣减都最终生成订单或回滚库存
redis.call('decr', stockKey)
redis.call('sadd', userListKey, userId)
redis.call('lpush', pendingKey, userId)

-- 4. 设置过期时间（如果不存在或剩余时间不足时更新）
local currentTTL = redis.call('ttl', stockKey)
if currentTTL == -1 or currentTTL < expireTime then
    redis.call('expire', stockKey, expireTime)
end
currentTTL = redis.call('ttl', userListKey)
if currentTTL == -1 or currentTTL < expireTime then
    redis.call('expire', userListKey, expireTime)
end
currentTTL = redis.call('ttl', pendingKey)
if currentTTL == -1 or currentTTL < expireTime then
    redis.call('expire', pendingKey, expireTime)
end

return 1 -- 秒杀成功