-- =============================================
-- 秒杀消息表版本号字段添加脚本
-- 用于支持两阶段确认的乐观锁控制
-- =============================================

-- 添加 version 字段（用于乐观锁控制）
-- 执行时机：在低峰期（凌晨 2-4 点）执行
-- 注意：执行前请先备份数据

ALTER TABLE seckill_message 
ADD COLUMN version BIGINT DEFAULT 0 COMMENT '版本号（用于乐观锁控制）' 
AFTER update_time;

-- 为现有数据设置初始版本号
UPDATE seckill_message SET version = 0 WHERE version IS NULL;

-- 验证字段是否添加成功
SELECT COUNT(*) AS total_messages, 
       COUNT(CASE WHEN version IS NOT NULL THEN 1 END) AS messages_with_version
FROM seckill_message;
