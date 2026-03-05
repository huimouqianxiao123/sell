-- AI 聊天消息持久化表
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：user/assistant/system/tool',
    event_type VARCHAR(30) NOT NULL DEFAULT 'message' COMMENT '事件类型',
    content TEXT NULL COMMENT '消息文本',
    extra_json TEXT NULL COMMENT '扩展数据(JSON)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_session_time (session_id, create_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天消息表';