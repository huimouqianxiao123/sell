package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.entity.SeckillProduct;
import com.example.sell.vo.SeckillProductDetailVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 屈轩
 */
public interface SeckillProductMapper extends BaseMapper<SeckillProduct> {

    /**
     * 使用乐观锁扣减秒杀商品库存
     * 
     * @param id      秒杀商品ID
     * @param version 版本号
     * @return 影响的行数
     */
    @Update("UPDATE seckill_product SET seckill_stock = seckill_stock - 1, version = version + 1 " +
            "WHERE id = #{id} AND seckill_stock > 0 AND version = #{version}")
    int decreaseStock(@Param("id") Long id, @Param("version") Long version);

    /**
     * 不带版本号的库存扣减（用于MQ消费者场景）
     * 因为Redis已经保证了库存的原子性扣减，这里只需要同步MySQL库存
     * 
     * @param id 秒杀商品ID
     * @return 影响的行数
     */
    @Update("UPDATE seckill_product SET seckill_stock = seckill_stock - 1, version = version + 1 " +
            "WHERE id = #{id} AND seckill_stock > 0")
    int decreaseStockWithoutVersion(@Param("id") Long id);

    /**
     * 查询秒杀商品列表（包含商品详情）
     * 
     * @return 秒杀商品详情列表
     */
    List<SeckillProductDetailVo> getSeckillProduct();

    /**
     * 查询活跃的秒杀商品（未开始、进行中、已售罄，或近24小时内结束的）
     * 用于对账补偿任务，需要覆盖所有可能有 pending 数据的商品
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM seckill_product WHERE status IN (0, 1, 3) AND (end_time IS NULL OR end_time > DATE_SUB(NOW(), INTERVAL 24 HOUR))")
    List<SeckillProduct> findActiveProducts();
}
