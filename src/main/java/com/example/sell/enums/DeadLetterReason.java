package com.example.sell.enums;

/**
 * 死信原因枚举
 * 
 * @author 屈轩
 */
public enum DeadLetterReason {
    
    /**
     * 临时故障 - 可立即重试
     * 例如：网络抖动、数据库锁、Redis 超时
     */
    TEMPORARY("临时故障"),
    
    /**
     * 业务异常 - 不应重试
     * 例如：重复购买、库存不足、商品不存在
     */
    BUSINESS("业务异常"),
    
    /**
     * 系统异常 - 需延迟重试
     * 例如：Redis 宕机、MQ 故障、数据库连接池耗尽
     */
    SYSTEM("系统异常"),
    
    /**
     * 未知原因 - 需要人工介入
     */
    UNKNOWN("未知原因");
    
    private final String description;
    
    DeadLetterReason(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
