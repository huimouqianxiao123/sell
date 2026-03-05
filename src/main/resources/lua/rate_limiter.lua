-- 滑动窗口限流 Lua 脚本
-- 参数说明：
-- KEYS[1]: 限流 Key（如 rate_limit:startSeckill:userId）
-- ARGV[1]: 窗口大小（秒）
-- ARGV[2]: 窗口内最大请求数
-- ARGV[3]: 当前时间戳（毫秒）
-- 返回 1=允许，0=拒绝

local key = KEYS[1]
local window = tonumber(ARGV[1])
local maxRequests = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 窗口起始时间
local windowStart = now - window * 1000

-- 清理窗口外的过期请求记录
redis.call('zremrangebyscore', key, '-inf', windowStart)

-- 获取窗口内的请求数
local currentCount = redis.call('zcard', key)

if currentCount >= maxRequests then
    return 0 -- 超过限制，拒绝
end

-- 添加当前请求记录
redis.call('zadd', key, now, now .. ':' .. math.random(1000000))

-- 设置 Key 过期时间（窗口大小 + 1秒冗余）
redis.call('expire', key, window + 1)

return 1 -- 允许
