package com.example.sell.domain.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author 屈轩
 * <P>商品管理页</P>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProductRequest {
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private String description;
    private String image;
    private Integer status;
}
