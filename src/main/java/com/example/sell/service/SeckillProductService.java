package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.domain.Dto.SeckillProductRequest;
import com.example.sell.domain.pojo.SeckillProduct;
import com.example.sell.domain.vo.SeckillProductDetailVo;

import java.util.List;
import java.util.Map;

/**
 * @author 屈轩
 */
public interface SeckillProductService extends IService<SeckillProduct> {
    void addSeckillProduct(SeckillProductRequest seckillProductRequest);

    String startSeckill(Long id);

    String getOrderNo();

    List<SeckillProductDetailVo> getSeckillProductList();

    /**
     * 查询秒杀订单数量（用于压测统计）
     * 
     * @param productId 秒杀商品ID
     * @return 包含订单数量、Redis库存等信息
     */
    Map<String, Object> getSeckillOrderCount(Long productId);

    /**
     * 统一的库存预热方法（定时任务、添加秒杀商品、请求兜底 共用）
     * 使用版本号原子性检查，防止旧数据覆盖新数据
     *
     * @param seckillProductId 秒杀商品ID
     */
    void warmUpSeckillStock(Long seckillProductId);

    /**
     * 统一计算缓存过期时间（预热和兜底共用同一策略）
     * 策略：endTime + 24小时，若无endTime则默认24小时
     */
    long calculateExpireSeconds(SeckillProduct seckillProduct);
}
