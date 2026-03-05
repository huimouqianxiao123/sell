package com.example.sell.node;

/**
 * OverAllState 状态 Key 常量定义
 * 消除工作流各节点之间的魔法字符串依赖
 *
 * @author 屈轩
 */
public final class StateKeys {

    private StateKeys() {
        // 工具类禁止实例化
    }

    /** 用户问题 */
    public static final String QUESTION = "question";

    /** 会话ID */
    public static final String SESSION_ID = "session_id";

    /** 问题分类结果（NORMAL/MARKETING/SECURITY/OTHER） */
    public static final String QUESTION_STYLE = "question_style";

    /** 历史对话上下文（格式化文本） */
    public static final String MEMORY_CONTEXT = "memory_context";

    /** Agent 回答 */
    public static final String AGENT_RESULT = "agent_result";

    /** HITL 中断标记 */
    public static final String HITL_INTERRUPTED = "hitl_interrupted";

    /** HITL 工具调用参数（JSON 字符串） */
    public static final String HITL_FEEDBACKS = "hitl_feedbacks";

    /** 原始用户消息（HITL 中断时保存） */
    public static final String ORIGINAL_MESSAGE = "original_message";

    /** 错误信息 */
    public static final String ERROR = "error";
}
