package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.entity.AiChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 聊天消息 Mapper
 */
@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {

    /**
     * 查询会话最近 N 条消息（按时间倒序）
     */
    @Select("SELECT * FROM ai_chat_message WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<AiChatMessage> findRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);
}