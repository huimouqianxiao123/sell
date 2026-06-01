package com.example.sell.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 本地消息表 - 用于保证秒杀消息不丢失
 * 先写本地数据库，再异步发送MQ，失败后定时任务补偿重发
 * 
 * 消息可靠性保障机制：
 * 1. 发送前持久化：先将消息写入本地消息表，确保消息不丢失
 * 2. 发送后确认：MQ发送成功后更新状态为已发送
 * 3. 消费后确认：消费者处理成功后更新状态为已消费
 * 4. 定时补偿：定时任务扫描失败消息进行重发
 * 5. 死信处理：超过最大重试次数的消息转入人工处理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("seckill_message")
public class SeckillMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一ID（用于幂等和追踪）
     */
    private String messageId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 秒杀商品ID
     */
    private Long seckillProductId;

    /**
     * 消息内容
     */
    private String messageContent;

    /**
     * 发送状态：0-待发送，1-发送成功，2-发送失败，3-已取消
     */
    private Integer status;

    /**
     * 消费状态：0-未消费，1-消费成功，2-消费失败
     */
    private Integer consumeStatus;

    /**
     * 发送重试次数
     */
    private Integer retryCount;

    /**
     * 消费重试次数
     */
    private Integer consumeRetryCount;

    /**
     * 最后一次重试时间
     */
    private LocalDateTime lastRetryTime;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * MQ消息ID（发送成功后由MQ返回）
     */
    private String mqMessageId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 版本号（用于乐观锁控制）
     */
    @Version
    @TableField(fill = FieldFill.INSERT)
    private Long version;

    // 发送状态常量
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SEND_SUCCESS = 1;
    public static final int STATUS_SEND_FAILED = 2;
    public static final int STATUS_CANCELLED = 3;

    // 消费状态常量
    public static final int CONSUME_STATUS_PENDING = 0;
    public static final int CONSUME_STATUS_SUCCESS = 1;
    public static final int CONSUME_STATUS_FAILED = 2;

    // 最大发送重试次数
    public static final int MAX_SEND_RETRY = 5;
    // 最大消费重试次数
    public static final int MAX_CONSUME_RETRY = 3;

    // 死信阶段最大重试次数（超过普通消费重试后继续补偿）
    public static final int MAX_DEAD_LETTER_RETRY = 10;
}
