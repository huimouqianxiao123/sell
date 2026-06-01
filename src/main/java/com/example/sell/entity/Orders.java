package com.example.sell.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 订单表
 * </p>
 *
 * @author YourName
 * @since 2026-01-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true) // 开启链式调用 new Order().setOrderNo(..).setAmount(..)
@TableName(value = "orders", autoResultMap = true)
public class Orders implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务订单号 (全局唯一，建议雪花算法)
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 订单类型: 1-普通订单, 2-秒杀订单
     */
    private Integer orderType;

    /**
     * 订单总金额
     */
    private BigDecimal totalAmount;

    /**
     * 状态: 10-待支付, 20-已支付, 30-已发货, 40-已完成, 50-已取消/超时关闭
     * 建议在代码中定义一个 Enum 枚举类来管理这些状态
     */
    private Integer status;

    /**
     * 第三方支付流水号 (支付宝 trade_no)
     */
    private String transactionId;

    /**
     * 支付成功时间
     */
    private LocalDateTime payTime;

    /**
     * 订单失效时间 (下单+15分钟，用于延迟队列关单)
     */
    private LocalDateTime expireTime;

    /**
     * 创建时间
     * fill = FieldFill.INSERT: 插入时自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     * fill = FieldFill.INSERT_UPDATE: 插入和更新时都自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除: 0-未删除, 1-已删除
     * @TableLogic: 调用 deleteById 时会自动转为 update set is_deleted=1
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 乐观锁版本号
     * @Version: 更新时会自动检查版本并 +1
     */
    @Version
    private Integer version;

    /**
     * 订单快照 (保存下单时的商品信息json)
     * typeHandler = JacksonTypeHandler.class: 自动将数据库 JSON 字符串转为 Java 对象/Map
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String snapshot; 
    // 注意：如果你想转成具体的对象，可以把类型改成 private SnapshotDTO snapshot;
}