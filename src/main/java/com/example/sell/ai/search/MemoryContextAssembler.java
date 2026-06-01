package com.example.sell.ai.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 组装短期上下文和 ES 召回的相关历史记忆。
 */
@Component
public class MemoryContextAssembler {

    public String assemble(String shortTermContext, List<AiSearchCandidate> keywordMemories, int maxChars) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(shortTermContext)) {
            builder.append("[短期对话记忆]\n")
                    .append(shortTermContext.trim());
        }

        if (keywordMemories != null && !keywordMemories.isEmpty()) {
            appendBlankLine(builder);
            builder.append("[相关历史记忆]\n");
            for (AiSearchCandidate memory : keywordMemories) {
                if (memory == null || !StringUtils.hasText(memory.getContent())) {
                    continue;
                }
                builder.append("- ")
                        .append(StringUtils.hasText(memory.getTitle()) ? memory.getTitle() : "历史记忆")
                        .append(": ")
                        .append(memory.getContent().trim())
                        .append("\n");
            }
        }

        String result = builder.toString().trim();
        if (maxChars > 0 && result.length() > maxChars) {
            return result.substring(0, maxChars);
        }
        return result;
    }

    private void appendBlankLine(StringBuilder builder) {
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
    }
}
