-- =============================================
-- 秒杀模块数据库索引优化脚本
-- 用于防止并发场景下的重复订单问题
-- =============================================

-- 1. 订单表添加唯一索引，防止同一用户同一商品重复创建秒杀订单
-- 场景：在并发场景下，两个相同的消息可能同时通过订单存在性检查
-- 作用：数据库层面的最后一道防线，确保数据一致性
ALTER TABLE orders 
ADD UNIQUE INDEX uk_user_product_order_type_status (
    user_id, 
    product_id, 
    order_type, 
    status
) COMMENT '防止用户重复购买同一商品的唯一索引';

-- 2. 秒杀消息表索引优化
-- 2.1 为查询待发送消息添加复合索引
CREATE INDEX idx_status_retry_create_time 
ON seckill_message(status, retry_count, create_time) 
COMMENT '用于定时任务查询待发送/失败消息';

-- 2.2 为查询消费失败消息添加复合索引
CREATE INDEX idx_status_consume_retry 
ON seckill_message(status, consume_status, consume_retry_count, update_time) 
COMMENT '用于定时任务查询消费失败消息';

-- 2.3 为查询超时未消费消息添加复合索引
CREATE INDEX idx_status_consume_create_time 
ON seckill_message(status, consume_status, create_time) 
COMMENT '用于定时任务查询超时未消费消息';

-- 2.4 为用户商品查询添加复合索引
CREATE INDEX idx_user_product_create_time 
ON seckill_message(user_id, seckill_product_id, create_time DESC) 
COMMENT '用于查询用户某商品的所有消息记录';

-- 3. 秒杀商品表索引优化
-- 3.1 为活跃商品查询添加复合索引
CREATE INDEX idx_status_end_time 
ON seckill_product(status, end_time) 
COMMENT '用于对账任务查询活跃秒杀商品';

-- 4. 订单表其他索引优化
-- 4.1 为查询用户秒杀订单添加复合索引
CREATE INDEX idx_user_order_type_status 
ON orders(user_id, order_type, status) 
COMMENT '用于查询用户的秒杀订单';

-- 4.2 为统计秒杀订单数量添加复合索引
CREATE INDEX idx_product_order_type 
ON orders(order_type, status) 
INCLUDE (id, user_id) 
COMMENT '用于统计某商品的秒杀订单数量';
