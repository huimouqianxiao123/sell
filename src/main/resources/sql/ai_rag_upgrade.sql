-- =============================================
-- AI RAG 升级脚本
-- 目标：
-- 1) 聊天消息增加 user_id，支持按 user/session/time 强约束检索
-- 2) 新建主知识库分片表，用于商品/活动/售后文档向量化
-- =============================================

ALTER TABLE ai_chat_message
    ADD COLUMN user_id VARCHAR(64) NULL COMMENT '用户ID（用于检索强约束）' AFTER session_id;

CREATE INDEX idx_user_session_time
    ON ai_chat_message(user_id, session_id, create_time);

CREATE TABLE IF NOT EXISTS ai_kb_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(64) NOT NULL COMMENT '知识类别(product/activity/after_sale)',
    source VARCHAR(255) NOT NULL COMMENT '来源标识（文件名或外部来源）',
    title VARCHAR(255) NULL COMMENT '标题',
    content TEXT NOT NULL COMMENT '分片内容',
    publish_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间/生效时间',
    active TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category_time (category, publish_time),
    INDEX idx_active_time (active, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI主知识库分片表';
