-- 秒杀库存预热脚本：原子性版本检查并设置库存，防止旧数据覆盖新数据
-- 参数说明：
-- KEYS[1]: 商品库存 Key (例如: seckill:stock:1001)
-- KEYS[2]: 库存版本 Key (例如: seckill:stock:version:1001)
-- ARGV[1]: 库存值
-- ARGV[2]: 版本号（时间戳，越大越新）
-- ARGV[3]: 过期时间（秒）
-- 返回 1=设置成功, 0=已有更新版本跳过

local stockKey = KEYS[1]
local versionKey = KEYS[2]
local stock = ARGV[1]
local version = ARGV[2]
local expireSeconds = tonumber(ARGV[3])

-- 检查是否已有更新版本的库存
local currentVersion = redis.call('get', versionKey)
if currentVersion and tonumber(currentVersion) >= tonumber(version) then
    return 0
end

-- 设置库存和版本号，并设置过期时间
redis.call('set', stockKey, stock, 'EX', expireSeconds)
redis.call('set', versionKey, version, 'EX', expireSeconds)

return 1
