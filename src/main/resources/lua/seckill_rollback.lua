-- 秒杀回滚 Lua 脚本：原子性恢复库存 + 移除用户 + 清理pending
-- 解决分步操作中途失败导致状态不一致的问题
--
-- 参数说明：
-- KEYS[1]: 商品库存 Key (例如: seckill:stock:1001)
-- KEYS[2]: 已购买用户集合 Key (例如: seckill:users:1001)
-- KEYS[3]: 待处理订单队列 Key (例如: seckill:pending:1001)
-- ARGV[1]: 用户 ID
-- 返回 1=回滚成功, 0=用户不在已购列表中(无需回滚)

local stockKey = KEYS[1]
local userListKey = KEYS[2]
local pendingKey = KEYS[3]
local userId = ARGV[1]

-- 检查用户是否在已购列表中，避免重复回滚导致库存虚增
if redis.call('sismember', userListKey, userId) == 0 then
    return 0 -- 用户不在已购列表，无需回滚
end

-- 原子性执行：恢复库存 + 移除用户 + 清理pending
redis.call('incr', stockKey)
redis.call('srem', userListKey, userId)
redis.call('lrem', pendingKey, 1, userId)

return 1 -- 回滚成功
