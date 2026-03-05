package com.example.sell.dao;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.domain.pojo.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 屈轩
 */
@Mapper
public interface OrderMapper extends BaseMapper<Orders> {

    /**
     * 检查用户对某秒杀商品是否已存在订单
     * 用于消费失败重试前判断是否需要重试
     *
     * @param userId 用户ID
     * @param productId 商品ID（秒杀商品的productId）
     * @return 订单数量，>0表示订单已存在
     */
    @Select("SELECT COUNT(*) FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE o.user_id = #{userId} AND oi.product_id = #{productId}")
    int countOrderByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 检查用户对某秒杀商品是否已存在有效秒杀订单（只检查秒杀订单）
     * 用于消费失败重试前判断是否需要重试
     * <p>
     * 过滤条件：
     * 1. order_type = 2（秒杀订单）
     * 2. status != 50（排除已取消的订单，避免误判）
     * 3. 24小时内创建（限制时间窗口，防止跨秒杀活动误判）
     * </p>
     *
     * @param userId 用户ID
     * @param productId 商品ID（秒杀商品的productId）
     * @return 订单数量，>0表示秒杀订单已存在
     */
    @Select("SELECT COUNT(*) FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE o.user_id = #{userId} AND oi.product_id = #{productId} " +
            "AND o.order_type = 2 AND o.status != 50 " +
            "AND o.create_time > DATE_SUB(NOW(), INTERVAL 24 HOUR)")
    int countSeckillOrderByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 查询用户某商品的最新有效秒杀订单号
     * 用于秒杀消费者幂等防重，避免重复扣减库存和重复创建订单
     */
    @Select("SELECT o.order_no FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE o.user_id = #{userId} AND oi.product_id = #{productId} " +
            "AND o.order_type = 2 AND o.status != 50 " +
            "ORDER BY o.create_time DESC LIMIT 1")
    String findLatestValidSeckillOrderNo(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 统计某商品在普通订单中的销售数量（用于库存预热时计算实际剩余库存）
     * 只统计普通订单（order_type = 1）
     *
     * @param productId 商品ID
     * @return 普通订单中该商品的销售数量
     */
    @Select("SELECT SUM(oi.quantity) FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE oi.product_id = #{productId} AND o.order_type = 1")
    int countNormalOrderByProduct(@Param("productId") Long productId);

    /**
     * 统计某商品的秒杀订单数量（用于压测统计）
     * 只统计秒杀订单（order_type = 2）
     *
     * @param productId 商品ID
     * @return 秒杀订单中该商品的数量
     */
    @Select("SELECT COUNT(*) FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE oi.product_id = #{productId} AND o.order_type = 2")
    int countSeckillOrderByProduct(@Param("productId") Long productId);

    /**
     * 统计某商品的有效秒杀订单数量（排除已取消的订单）
     * 用于库存预热时计算实际已售数量
     *
     * @param productId 商品ID
     * @return 有效秒杀订单数量（order_type=2 且 status!=50）
     */
    @Select("SELECT COUNT(*) FROM orders o " +
            "INNER JOIN order_item oi ON o.id = oi.order_id " +
            "WHERE oi.product_id = #{productId} AND o.order_type = 2 AND o.status != 50")
    int countActiveSeckillOrderByProduct(@Param("productId") Long productId);
}
