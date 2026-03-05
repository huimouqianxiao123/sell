package com.example.sell.domain.pojo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @author 屈轩
 * <p>订单明细表</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("order_item")
public class OrderItem {
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 归属的订单ID (外键)
     */
    @TableField("order_id")
    private Long orderId;

    /**
     * 归属的订单号 (冗余字段，方便不查主表直接定位)
     */
    @TableField("order_no")
    private String orderNo;

    /**
     * 商品ID (关联商品表)
     */
    @TableField("product_id")
    private Long productId;

    /**
     * 商品名称 (快照)
     */
    @TableField("product_name")
    private String productName;

    /**
     * 商品图片 (快照)
     */
    @TableField("product_image")
    private String productImage;

    /**
     * 商品规格 (快照，例如: "颜色:红; 尺码:XL")
     */
    @TableField("product_description")
    private String productDescription;

    /**
     * 单价 (下单时的价格，不要去关联商品表查)
     */
    @TableField("unit_price")
    private BigDecimal unitPrice;

    /**
     * 购买数量
     */
    @TableField("quantity")
    private Integer quantity;

    /**
     * 本项总价 (unit_price * quantity)
     */
    @TableField("total_price")
    private BigDecimal totalPrice;


    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}
