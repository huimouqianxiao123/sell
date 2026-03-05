package com.example.sell.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀商品详情VO
 *
 * @author 屈轩
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeckillProductDetailVo implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id; // 秒杀商品ID
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId; // 商品ID
    private BigDecimal seckillPrice; // 秒杀价格
    private Integer seckillStock; // 秒杀库存
    private Integer status; // 状态：0-未开始，1-进行中，2-已结束，3-已售罄
    private String startTime; // 开始时间
    private String endTime; // 结束时间
    private String name; // 商品名称
    private String description; // 商品描述
    private String imageUrl; // 商品图片URL
}
