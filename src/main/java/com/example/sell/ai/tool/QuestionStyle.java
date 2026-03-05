package com.example.sell.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author 屈轩
 */
@Getter
@AllArgsConstructor
public enum QuestionStyle {
    NORMAL("通用问题"),
    MARKETING("营销"),
    SECURITY("安全"),
    OTHER("其他");


    private final String style;
}