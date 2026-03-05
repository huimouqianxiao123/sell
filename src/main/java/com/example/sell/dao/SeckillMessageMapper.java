package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.domain.pojo.SeckillMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 秒杀消息Mapper
 * 提供消息可靠性保障所需的各种查询和更新操作
 */
@Mapper
public interface SeckillMessageMapper extends BaseMapper<SeckillMessage> {

    /**
     * 查询待发送或发送失败的消息（用于定时任务补偿）
     * 条件：
     * - 发送失败(status=2)且重试次数未超过最大值
     * - 或待发送(status=0)但创建时间超过30秒（防止异步发送中的消息被重复发送）
     */
    @Select("SELECT * FROM seckill_message WHERE " +
            "(status = 2 AND retry_count < #{maxRetry}) " +
            "OR (status = 0 AND create_time < DATE_SUB(NOW(), INTERVAL 30 SECOND) AND retry_count < #{maxRetry}) " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillMessage> findPendingMessages(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 查询发送成功但消费失败的消息（用于消费补偿）
     * 条件：发送状态为成功(1)，消费状态为失败(2)，且消费重试次数未超过最大值
     */
    @Select("SELECT * FROM seckill_message WHERE status = 1 AND consume_status = 2 AND consume_retry_count < #{maxRetry} ORDER BY update_time ASC LIMIT #{limit}")
    List<SeckillMessage> findConsumeFailedMessages(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 查询发送成功但长时间未消费的消息（可能消费端出问题）
     * 条件：发送状态为成功(1)，消费状态为待消费(0)，创建时间超过指定分钟数
     */
    @Select("SELECT * FROM seckill_message WHERE status = 1 AND consume_status = 0 AND create_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillMessage> findTimeoutMessages(@Param("minutes") int minutes, @Param("limit") int limit);

    /**
     * 查询死信消息（超过最大重试次数的消息）
     * 条件：发送失败且重试次数已达上限，或消费失败且消费重试次数已达上限
     */
    @Select("SELECT * FROM seckill_message WHERE (status = 2 AND retry_count >= #{maxSendRetry}) OR (status = 1 AND consume_status = 2 AND consume_retry_count >= #{maxConsumeRetry}) ORDER BY update_time ASC LIMIT #{limit}")
    List<SeckillMessage> findDeadLetterMessages(@Param("maxSendRetry") int maxSendRetry,
                                                @Param("maxConsumeRetry") int maxConsumeRetry, @Param("limit") int limit);

    /**
     * 根据消息ID查询消息
     */
    @Select("SELECT * FROM seckill_message WHERE message_id = #{messageId}")
    SeckillMessage findByMessageId(@Param("messageId") String messageId);

    /**
     * 更新消息发送状态和MQ消息ID
     */
    @Update("UPDATE seckill_message SET status = #{status}, mq_message_id = #{mqMessageId}, update_time = NOW() WHERE id = #{id}")
    int updateSendStatus(@Param("id") Long id, @Param("status") int status, @Param("mqMessageId") String mqMessageId);

    /**
     * 更新消息消费状态
     */
    @Update("UPDATE seckill_message SET consume_status = #{consumeStatus}, update_time = NOW() WHERE message_id = #{messageId}")
    int updateConsumeStatus(@Param("messageId") String messageId, @Param("consumeStatus") int consumeStatus);

    /**
     * 更新消费失败信息
     */
    @Update("UPDATE seckill_message SET consume_status = #{consumeStatus}, consume_retry_count = consume_retry_count + 1, fail_reason = #{failReason}, update_time = NOW() WHERE message_id = #{messageId}")
    int updateConsumeFailure(@Param("messageId") String messageId, @Param("consumeStatus") int consumeStatus,
                             @Param("failReason") String failReason);

    /**
     * 增加发送重试次数并更新状态
     */
    @Update("UPDATE seckill_message SET retry_count = retry_count + 1, last_retry_time = NOW(), status = #{status}, fail_reason = #{failReason}, update_time = NOW() WHERE id = #{id}")
    int incrementRetryCount(@Param("id") Long id, @Param("status") int status, @Param("failReason") String failReason);

    /**
     * 查询用户某商品是否已有待处理或成功的消息（防止重复发送）
     */
    @Select("SELECT COUNT(*) FROM seckill_message WHERE user_id = #{userId} AND seckill_product_id = #{seckillProductId} AND status IN (0, 1) AND (consume_status IS NULL OR consume_status IN (0, 1))")
    int countPendingMessage(@Param("userId") Long userId, @Param("seckillProductId") Long seckillProductId);

    /**
     * 查询用户某商品的所有消息记录（用于对账补偿任务判断消息完整状态）
     * 包含所有状态的消息，不限制status和consume_status
     */
    @Select("SELECT * FROM seckill_message WHERE user_id = #{userId} AND seckill_product_id = #{seckillProductId} ORDER BY create_time DESC LIMIT 1")
    SeckillMessage findByUserAndProduct(@Param("userId") Long userId, @Param("seckillProductId") Long seckillProductId);

    /**
     * 查询用户某商品是否已消费成功（用于对账补偿任务判断订单是否已生成）
     */
    @Select("SELECT COUNT(*) FROM seckill_message WHERE user_id = #{userId} AND seckill_product_id = #{seckillProductId} AND consume_status = 1")
    int countConsumeSuccess(@Param("userId") Long userId, @Param("seckillProductId") Long seckillProductId);

    /**
     * 统计某秒杀商品已售出数量（用于库存预热时计算实际剩余库存）
     * 统计条件：status = 1 表示已发送成功，consume_status = 1 表示消费成功
     * 只统计真正生成订单的消息，排除消费失败的消息
     */
    @Select("SELECT COUNT(*) FROM seckill_message WHERE seckill_product_id = #{seckillProductId} AND status = 1 AND consume_status = 1")
    int countSoldBySeckillProductId(@Param("seckillProductId") Long seckillProductId);

    /**
     * 统计某秒杀商品的在途消息数量（已扣减Redis库存但DB库存尚未扣减）
     * <p>
     * 用于库存预热时修正剩余库存：正确Redis库存 = DB.seckillStock - 在途消息数
     * </p>
     * 在途条件：消息已创建(status IN 0,1) 且 尚未消费成功(consume_status为NULL或0)
     * <ul>
     *   <li>consume_status=1（消费成功）→ DB已扣减，不算在途</li>
     *   <li>consume_status=2（消费失败）→ Redis已回滚，不算在途</li>
     * </ul>
     *
     * @param seckillProductId 秒杀商品ID
     * @return 在途消息数量
     */
    @Select("SELECT COUNT(*) FROM seckill_message WHERE seckill_product_id = #{seckillProductId} " +
            "AND status IN (0, 1) AND (consume_status IS NULL OR consume_status = 0)")
    int countInFlightBySeckillProductId(@Param("seckillProductId") Long seckillProductId);

    /**
     * 预确认消费（记录消费开始时间和状态）
     * 用于两阶段确认的第一阶段
     *
     * @param messageId 消息 ID
     * @param consumeStartTime 消费开始时间
     * @return 影响的行数
     */
    @Update("UPDATE seckill_message SET consume_status = 0, last_retry_time = #{consumeStartTime}, update_time = NOW() " +
            "WHERE message_id = #{messageId} AND status = 1")
    int preConfirmConsume(@Param("messageId") String messageId, @Param("consumeStartTime") java.time.LocalDateTime consumeStartTime);

    /**
     * 最终确认消费（带版本号控制，防止并发覆盖）
     * 用于两阶段确认的第二阶段
     *
     * @param messageId 消息 ID
     * @param consumeStatus 消费状态
     * @param expectedVersion 期望的版本号（用于乐观锁检查）
     * @return 影响的行数（0 表示版本不匹配或消息不存在）
     */
    @Update("UPDATE seckill_message SET consume_status = #{consumeStatus}, version = version + 1, " +
            "update_time = NOW() WHERE message_id = #{messageId} AND version = #{expectedVersion}")
    int updateConsumeStatusWithVersion(@Param("messageId") String messageId, 
                                       @Param("consumeStatus") int consumeStatus,
                                       @Param("expectedVersion") Long expectedVersion);

    /**
     * 查询消息的当前版本号
     *
     * @param messageId 消息 ID
     * @return 版本号
     */
    @Select("SELECT version FROM seckill_message WHERE message_id = #{messageId}")
    Long getVersionByMessageId(@Param("messageId") String messageId);
}
