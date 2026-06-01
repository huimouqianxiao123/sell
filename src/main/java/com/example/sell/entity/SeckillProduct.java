package com.example.sell.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品实体
 *
 * @author 屈轩
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("seckill_product")
public class SeckillProduct implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 商品ID */
    @TableField("product_id")
    private Long productId;

    /** 秒杀价格 */
    @TableField("seckill_price")
    private BigDecimal seckillPrice;

    /** 秒杀库存 */
    @TableField("seckill_stock")
    private Integer seckillStock;

    @TableField("version")
    private Long version;

    /** 开始时间 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 结束时间 */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 状态：0-未开始，1-进行中，2-已结束，3-已售罄 */
    @TableField("status")
    private Integer status;

    /* ==================== 常用方法 ==================== */

    @JsonIgnore
    public boolean isStarted() {
        return LocalDateTime.now().isAfter(startTime);
    }

    @JsonIgnore
    public boolean isEnded() {
        return LocalDateTime.now().isAfter(endTime);
    }

    @JsonIgnore
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(startTime) && now.isBefore(endTime);
    }

    @JsonIgnore
    public boolean hasStock() {
        return seckillStock != null && seckillStock > 0;
    }
}